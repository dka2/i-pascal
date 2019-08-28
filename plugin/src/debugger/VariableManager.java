package com.siberika.idea.pascal.debugger;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.siberika.idea.pascal.PascalBundle;
import com.siberika.idea.pascal.debugger.gdb.GdbExecutionStack;
import com.siberika.idea.pascal.debugger.gdb.GdbStackFrame;
import com.siberika.idea.pascal.debugger.gdb.GdbVariableObject;
import com.siberika.idea.pascal.debugger.gdb.parser.GdbMiLine;
import com.siberika.idea.pascal.debugger.gdb.parser.GdbMiResults;
import com.siberika.idea.pascal.lang.parser.NamespaceRec;
import com.siberika.idea.pascal.lang.psi.PasEntityScope;
import com.siberika.idea.pascal.lang.psi.impl.PasField;
import com.siberika.idea.pascal.lang.references.ResolveContext;
import com.siberika.idea.pascal.lang.references.resolve.Resolve;
import com.siberika.idea.pascal.lang.references.resolve.ResolveProcessor;
import com.siberika.idea.pascal.util.StrUtil;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VariableManager {

    private static final Logger LOG = Logger.getInstance(VariableManager.class);

    private static final String VAR_PREFIX_LOCAL = "l%";
    private static final String VAR_PREFIX_WATCHES = "w%";
    private static final String OPEN_ARRAY_HIGH_BOUND_VAR_PREFIX = "high";
    private static final List<String> TYPES_STRING = Arrays.asList("ANSISTRING", "WIDESTRING", "UNICODESTRING", "UTF8STRING", "RAWBYTESTRING");
    private static final List<String> TYPES_PCHAR = Arrays.asList("PCHAR", "PWIDECHAR", "PUNICODECHAR");
    private static final Map<Long, String> CODEPAGE_MAP = createCodePageMap();
    static final CommandSender.FinishCallback SILENT = res -> {};
    private static final Pattern PATTERN_STRING_VALUE = Pattern.compile("(0x[0-9a-f]+)(\\s((\\\\\")|').*)?");
    private final PascalCExpressionTranslator expressionTranslator = new PascalCExpressionTranslator();
    private static final List<String> SYNTHETIC_CHILDS = Arrays.asList("private", "protected", "public", "published");
    private static final AtomicInteger queryCounter = new AtomicInteger();

    private static Map<Long, String> createCodePageMap() {
        HashMap<Long, String> res = new HashMap<>();
        res.put(0L, "ACP");
        res.put(1L, "OEMCP");
        res.put(1200L, "UTF16");
        res.put(1201L, "UTF16BE");
        res.put(65000L, "UTF7");
        res.put(65001L, "UTF8");
        res.put(20127L, "ASCII");
        res.put(65535L, "NONE");
        return res;
    }

    private final PascalXDebugProcess process;

    private Map<String, GdbVariableObject> variableObjectMap;
    private final Map<String, PasField> fieldsMap = new ConcurrentHashMap<>();

    VariableManager(PascalXDebugProcess process) {
        this.process = process;
        this.variableObjectMap = new LinkedHashMap<>();
    }

    public void queryVariables(int level, GdbExecutionStack executionStack, GdbStackFrame frame) {
        process.sendCommand("-stack-select-frame " + level);
        if (process.backend.options.supportsBulkDelete) {
            process.sendCommand("-var-delete *");
        }
        variableObjectMap.values().removeIf(v -> !v.isWatched());
        int qc = queryCounter.incrementAndGet();
        // TODO: resolve and add global variables
        process.sendCommand(String.format("-stack-list-variables --thread %s --frame %d --no-values", executionStack.getThreadId(), level),
                res -> {
                    if (res.getResults().getValue("variables") != null) {
                        handleVariablesResponse(frame, res.getResults().getList("variables"));
                    } else {
                        LOG.info(String.format("DBG Error: Invalid debugger response for variables: %s", res.toString()));
                    }
                });
        process.syncCalls(5, res -> {
            if (queryCounter.get() == qc) {
                frame.refreshVarTree(variableObjectMap.values());
            }
        });
    }

    // handling of -stack-list-variables command
    private void handleVariablesResponse(GdbStackFrame frame, List<Object> variables) {
        for (Object o : variables) {
            if (o instanceof GdbMiResults) {
                GdbMiResults res = (GdbMiResults) o;
                String varName = res.getString("name");
                final String varKey = getVarKey(varName, false, VAR_PREFIX_LOCAL);
                if (!process.backend.options.supportsBulkDelete) {
                    process.sendCommand("-var-delete " + varKey, SILENT);
                }
                process.backend.createVar(varKey, varName, null);
                GdbVariableObject var = new GdbVariableObject(frame, varKey, varName, varName, null, res);
                variableObjectMap.put(varKey, var);
                resolveVariable(var);
            } else {
                LOG.error(String.format("DBG Error: Invalid variables list entry: %s", o));
            }
        }
    }

    // handling of -var-create command
    void handleVarResult(GdbMiResults res) {
        handleVarData(null, res);
    }

    private void handleVarData(GdbVariableObject parent, GdbMiResults res) {
        XStackFrame frame = process.getCurrentFrame();
        if (frame instanceof GdbStackFrame) {
            String varName = res.getString("name");
            String varKey;
            if (varName.startsWith(VAR_PREFIX_LOCAL) || varName.startsWith(VAR_PREFIX_WATCHES)) {
                varKey = varName;
            } else {
                LOG.info("=== DBG Error: name w/o prefix: " + varName);
                varKey = getVarKey(varName, false, VAR_PREFIX_LOCAL);
            }
            GdbVariableObject var;
            if (parent != null) {
                String id = removeSyntheticLevels(varName);
                var = new GdbVariableObject((GdbStackFrame) frame, varKey, varName, id, null);
                parent.getChildren().add(var);
                variableObjectMap.put(varKey, var);
                resolveVariable(var);
            } else {
                var = variableObjectMap.get(varKey);
            }
            if (var != null) {
                var.updateFromResult(res);
                refineStructured(var, res);
                refineOpenArray(var, res);
                refineDynamicArray(var, res);
                refineString(var, res);
                refineSet(var);
                updateVariableObjectUI(var);
            } else {
                LOG.info("DBG Error: variable not found: " + varKey);
            }
        }
    }

    // handling of -var-update command
    void handleVarUpdate(GdbMiResults results) {
        XStackFrame frame = process.getCurrentFrame();
        if (frame instanceof GdbStackFrame) {
            List<Object> changes = results.getList("changelist");
            for (Object o : changes) {
                GdbMiResults change = (GdbMiResults) o;
            }
        }
    }

    private void resolveVariable(GdbVariableObject var) {
        String varNameResolved = var.getExpression();
        PasField.FieldType fieldType = PasField.FieldType.VARIABLE;
        boolean hidden = isHidden(var.getName());
        if ("this".equalsIgnoreCase(var.getName())) {
            varNameResolved = "Self";
        } else if (!hidden) {
            PasField field = resolveIdentifierName(process.getCurrentFrame().getSourcePosition(), var.getExpression(), PasField.TYPES_LOCAL);
            if (field != null) {
                varNameResolved = formatVariableName(field);
                fieldType = field.fieldType;
                if (fieldType == PasField.FieldType.ROUTINE) {
                    hidden = true;
                }
            }
        }
        var.setExpression(varNameResolved);
        var.setFieldType(fieldType);
        var.setVisible(!hidden);
    }

    void removeVariable(String varKey) {
        XStackFrame frame = process.getCurrentFrame();
        if (frame instanceof GdbStackFrame) {
            variableObjectMap.remove(varKey);
        }
    }

    void computeValueChildren(String name, XCompositeNode node) {
        GdbVariableObject tempParent = findVarObject(name);
        if (tempParent != null) {
            process.sendCommand("-var-list-children --all-values " + name + " 0 " + process.backend.options.limitChilds, new CommandSender.FinishCallback() {
                @Override
                public void call(GdbMiLine res) {
                    /*if ("0".equals(res.getResults().getString("numchild"))) {
                        tempParent.getFrame().refreshVarTree(node, Collections.emptyList());
                        return;
                    }*/
                    final List<Object> children = res.getResults() != null ? res.getResults().getList("children") : null;
                    if (children != null) {
                        for (Object variable : children) {
                            if (variable instanceof GdbMiResults) {
                                final GdbMiResults child = ((GdbMiResults) variable).getTuple("child");
                                if (child != null) {
                                    String childName = child.getString("name");
                                    if (isChildFiltered(childName)) {
                                        continue;
                                    }
                                    if (isChildSynthetic(childName)) {
                                        process.sendCommand("-var-list-children --all-values " + childName + " 0 " + process.backend.options.limitChilds, this);
                                    } else {
                                        handleVarData(tempParent, child);
                                    }
                                } else {
                                    LOG.info("DBG Error: invalid chldren entry: " + res);
                                }
                            }
                        }
                    }
                }
            });
            process.syncCalls(6, new CommandSender.FinishCallback() {
                @Override
                public void call(GdbMiLine res) {
                    tempParent.getFrame().refreshVarTree(node, tempParent.getChildren());
                }
            });
        } else {
            LOG.info("DBG Error: child not found: " + name);
        }
    }

    private String removeSyntheticLevels(String name) {
        name = name.substring(name.lastIndexOf('%') + 1);
        for (String syntheticChild : SYNTHETIC_CHILDS) {
            name = name.replaceAll("\\." + syntheticChild, "");
        }
        return name;
    }

    private boolean isChildSynthetic(String childName) {
        return SYNTHETIC_CHILDS.stream().anyMatch(childName::endsWith);
    }

    private boolean isChildFiltered(String childName) {
        return childName.endsWith("vmt");
    }

    private GdbVariableObject findVarObject(String name) {
        GdbVariableObject res = variableObjectMap.get(name);
        /*int prevI = name.length();
        int index = name.substring(0, prevI).lastIndexOf('.');
        while ((null == res) && (index > 0)) {
            String levelName = name.substring(0, index);
            GdbVariableObject parent = variableObjectMap.get(levelName);
            if (parent != null) {
                res = parent.findChild(name);
            }
            prevI = index;
            index = name.substring(0, prevI).lastIndexOf('.');
        }*/
        return res;
    }

    private void refineStructured(GdbVariableObject var, GdbMiResults res) {
        if (!process.backend.options.refineStructured) {
            return;
        }
        if (isStructured(var)) {
            process.sendCommand(String.format("-data-evaluate-expression %s", removeSyntheticLevels(var.getName())), new CommandSender.FinishCallback() {
                @Override
                public void call(GdbMiLine res) {
                    String value = DebugUtil.retrieveResultValue(res);
                    if (value != null) {
                        Pattern PATTERN_VTYPE = Pattern.compile("VTYPE = (\\d{1,3})");
                        Matcher matcher = PATTERN_VTYPE.matcher(value);
                        if (matcher.find()) {
                            int vType = Integer.parseInt(matcher.group(1));
                            if (vType == 0) {
                                Pattern PATTERN_VALUE = Pattern.compile("VINTEGER = (\\d+)");
                                Matcher m = PATTERN_VALUE.matcher(value);
                                if (m.find()) {
                                    value = m.group(1);
                                }
                            }
                        }
                        var.setValueRefined(value);
                    } else {
                        var.setError(PascalBundle.message("debug.expression.no.result"));
                    }
                }
            });
        }
    }

    private void refineOpenArray(GdbVariableObject highBoundVar, GdbMiResults res) {
        if (!process.backend.options.refineOpenArrays) {
            return;
        }
        if (highBoundVar.getName().startsWith(OPEN_ARRAY_HIGH_BOUND_VAR_PREFIX)) {
            Integer highIndex = res.getInteger("value");
            if (highIndex != null) {
                final String openArrayName = highBoundVar.getName().substring(4);
                GdbVariableObject openArrayVar = variableObjectMap.get(getVarKey(openArrayName, false, VAR_PREFIX_LOCAL));
                if (openArrayVar != null) {
                    openArrayVar.setChildrenCount(0);
                    openArrayVar.setLength(highIndex + 1);
                    if (openArrayVar.getLength() != 0) {
                        long displayLength = Math.min(openArrayVar.getLength(), process.backend.options.limitElements);
                        openArrayVar.setAdditional(Long.toString(openArrayVar.getLength()));
                        process.backend.queryArrayValue(openArrayVar, 0, displayLength);
                    } else {
                        openArrayVar.setValueRefined("[]");
                    }
                } else {
                    LOG.info(String.format("DBG Error: no array variable found for bound param %s", highBoundVar.getName()));
                }
            }
        }
    }

    private void refineDynamicArray(GdbVariableObject var, GdbMiResults res) {
        if (!process.backend.options.refineDynamicArrays) {
            return;
        }
        String type = res.getString("type");
        if (isDynamicArray(type)) {
            String addressStr = res.getString("value");
            if (addressStr != null) {
                if (!isValidAddress(addressStr)) {
                    return;
                }
                int size = addressStr.length() > 8 ? 8 : 4;
                process.sendCommand(String.format("-data-read-memory-bytes -o -%d %s %d", size * 2, removeSyntheticLevels(var.getName()), size * 2), new CommandSender.FinishCallback() {
                    @Override
                    public void call(GdbMiLine res) {
                        List<Object> memory = res.getResults().getValue("memory") != null ? res.getResults().getList("memory") : null;
                        GdbMiResults tuple = ((memory != null) && (memory.size() > 0)) ? (GdbMiResults) memory.get(0) : null;
                        String content = tuple != null ? tuple.getString("contents") : null;
                        if ((content != null) && (content.length() == (size * 2 * 2))) {
                            long refCount = DebugUtil.parseHex(content.substring(0, size * 2));
                            long length = DebugUtil.parseHex(content.substring(size * 2)) + 1;
                            long displayLength = Math.min(length, process.backend.options.limitElements);
                            var.setLength(length);
                            var.setAdditional(var.getLength() + "#" + refCount);
                            process.backend.queryArrayValue(var, 0, displayLength);
                        } else {
                            LOG.info(String.format("DBG Error: Invalid debugger response for memory: %s", res.toString()));
                            var.setError(PascalBundle.message("debug.error.memory.read", var.getName()));
                        }
                    }
                });
            }
        }
    }

    private void refineString(GdbVariableObject var, GdbMiResults res) {
        if (!process.backend.options.refineStrings) {
            return;
        }
        String type = res.getString("type");
        if (isPChar(type)) {
            return;
        }
        String addressStr = parseStringAddress(type, res.getString("value"));
        if (addressStr != null) {
            var.setChildrenCount(0);
            if (isValidAddress(addressStr)) {
                boolean hasCP = hasCodepageInfo(type);
                boolean hasRefcount = hasRefcountInfo(type);
                int headSize = process.backend.options.pointerSize * (1 + (hasRefcount ? 1 : 0) + (hasCP ? 1 : 0));
                process.sendCommand(String.format("-data-read-memory-bytes -o -%d %s %d", headSize, removeSyntheticLevels(var.getName()), headSize), new CommandSender.FinishCallback() {
                    @Override
                    public void call(GdbMiLine res) {
                        String content = parseReadMemory(res);
                        if ((content != null) && (content.length() == (headSize * 2))) {
                            int base = 0;
                            Integer elemSize = null;
                            Integer codepage = null;
                            if (hasCP) {
                                codepage = (int) DebugUtil.parseHex(content.substring(0, 4));
                                elemSize = (int) DebugUtil.parseHex(content.substring(4, 8));
                                base = process.backend.options.pointerSize * 2;
                            }
                            Long codepageFinal = codepage != null ? codepage.longValue() : null;
                            Long refCount = null;
                            if (hasRefcount) {
                                refCount = DebugUtil.parseHex(content.substring(base, base + process.backend.options.pointerSize * 2));
                                base = base + process.backend.options.pointerSize * 2;
                            }
                            Long refCountFinal = refCount;
                            long length = DebugUtil.parseHex(content.substring(base, base + process.backend.options.pointerSize * 2));
                            long displayLength = Math.min(length, process.backend.options.limitChars);
                            int charSize = getCharSize(elemSize, type);
                            long dataSize = isSizeInBytes(type) ? displayLength : displayLength * charSize;
                            process.sendCommand(String.format("-data-read-memory-bytes %s %d", removeSyntheticLevels(var.getName()), dataSize), new CommandSender.FinishCallback() {
                                        @Override
                                        public void call(GdbMiLine res) {
                                            String content = parseReadMemory(res);
                                            if (content != null) {
                                                var.setValueRefined(parseString(content, length, displayLength, charSize));
                                                var.setAdditional(length + (refCountFinal != null ? "#" + refCountFinal.toString() : "") + printCodepage(codepageFinal));
                                                updateVariableObjectUI(var);
                                            } else {
                                                LOG.info(String.format("DBG Error: Invalid debugger response for memory: %s", res.toString()));
                                            }
                                        }
                                    }
                            );
                        }
                    }
                });
            }
        }
    }

    private void refineSet(GdbVariableObject var) {
        if (isSet(var)) {
            process.sendCommand("-data-evaluate-expression \"sizeof " + removeSyntheticLevels(var.getName()) + "\"", res -> {
                Integer size = DebugUtil.retrieveResultValueInt(res);
                if (size != null) {
                    var.setAdditional(size + "b");
                }
            });
            String value = var.getValue();
            BigInteger valInt = StrUtil.strToBigIntDef(value, null);
            StringBuilder sb = new StringBuilder("[");
            if (valInt != null) {
                addSetBits(sb, valInt, 0);
            } else {
                if (StrUtil.startsWith(value, "0x")) {
                    value = value.substring(2);
                    for (int i = value.length() - 1; i >= 0; i--) {
                        addSetBits(sb, BigInteger.valueOf(Integer.parseInt(String.valueOf(value.charAt(i)), 16)), (value.length() - i - 1) * 4);
                    }
                }
            }
            sb.append("]");
            var.setValueRefined(sb.toString());
        }
    }

    private void addSetBits(StringBuilder sb, BigInteger bits, int startBit) {
        int lastIndex = bits.bitLength();
        for (int i = 0; i < lastIndex; i++) {
            if (bits.testBit(i)) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(startBit + i);
            }
        }
    }

    private static String formatVariableName(@NotNull PasField field) {
        return field.name + (field.fieldType == PasField.FieldType.ROUTINE ? "()" : "");
    }

    private static String getVarKey(String varName, boolean children, String prefix) {
        return (children ? "" : prefix) + varName.replace(' ', '_');
    }

    private static boolean isHidden(String varName) {
        return varName.startsWith(OPEN_ARRAY_HIGH_BOUND_VAR_PREFIX) || "result".equals(varName);
    }

    private PasField resolveIdentifierName(XSourcePosition sourcePosition, final String name, final Set<PasField.FieldType> types) {
        if (!process.backend.options.resolveNames() || (null == sourcePosition)) {
            return null;
        }
        if (DumbService.isDumb(process.getSession().getProject())) {
            return null;
        } else {
            return ApplicationManager.getApplication().runReadAction(new Computable<PasField>() {
                @Override
                public PasField compute() {
                    PsiElement el = XDebuggerUtil.getInstance().findContextElement(sourcePosition.getFile(), sourcePosition.getOffset(), process.getSession().getProject(), false);
                    if (el != null) {
                        return getFields(el, name, types);
                    }
                    return null;
                }
            });
        }
    }

    private PasField getFields(@NotNull PsiElement el, String name, Set<PasField.FieldType> types) {
        PasField field = fieldsMap.get(name);
        if (field != null) {
            return field;
        }
        NamespaceRec namespace;
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            if (name.startsWith("this.")) {
                namespace = NamespaceRec.fromFQN(el, name.substring(5));
            } else {
                namespace = NamespaceRec.fromFQN(el, name);
            }
        } else {
            namespace = NamespaceRec.fromFQN(el, name);
        }
        namespace.setIgnoreVisibility(true);
        Resolve.resolveFQN(namespace, new ResolveContext(PasField.TYPES_LOCAL, true), new ResolveProcessor() {
                    @Override
                    public boolean process(PasEntityScope originalScope, PasEntityScope scope, PasField field, PasField.FieldType type) {
                        if (types.contains(field.fieldType)) {
                            fieldsMap.put(name, field);
                            return false;
                        }
                        return true;
                    }
                });
        return fieldsMap.get(name);
    }

    public void evaluate(GdbStackFrame frame, String expression, XDebuggerEvaluator.XEvaluationCallback callback, XSourcePosition expressionPosition) {
        String debuggerExpression = expressionTranslator.translate(expression);
        String key = getVarKey(debuggerExpression, false, VAR_PREFIX_WATCHES);
        final GdbVariableObject var = new GdbVariableObject(frame, key, debuggerExpression, expression, callback);
        variableObjectMap.put(key, var);
        process.sendCommand("-var-delete " + key, SILENT);
        process.backend.createVar(key, var.getName(),
                new CommandSender.FinishCallback() {
                    @Override
                    public void call(GdbMiLine res) {
                        if ((res.getType() == GdbMiLine.Type.RESULT_RECORD) && ("error".equals(res.getRecClass()))) {
                            var.setError(res.getResults().getString("msg"));
                            callback.evaluated(new PascalDebuggerValue(var));
                        } else {
                            handleVarResult(res.getResults());
                        }
                    }
                });
    }

    private String parseStringAddress(String type, String value) {
        Matcher m = value != null ? PATTERN_STRING_VALUE.matcher(value) : null;
        if ((m != null) && m.matches()) {
            return ((m.group(2) != null) || isString(type)) ? m.group(1) : null;
        } else {
            return null;
        }
    }

    private String parseString(String content, long length, long displayLength, int charSize) {
        try {
            String str = null;
            byte[] data = Hex.decodeHex(content.toCharArray());
            if (charSize == 1) {
                str = new String(data, StandardCharsets.UTF_8);
            } else if (charSize == 2) {
                str = new String(data, StandardCharsets.UTF_16LE);
            }
            if ((str != null) && process.backend.options.showNonPrintable) {
                StringBuilder sb = new StringBuilder(str.length() + str.length() / 4);
                str.chars().forEachOrdered(new IntConsumer() {
                    @Override
                    public void accept(int value) {
                        if (value >= 32) {
                            sb.append((char) value);
                        } else {
                            sb.append("'#").append(value).append('\'');
                        }
                    }
                });
                str = sb.toString();
            }
            String termStr = displayLength == length ? "'" : "...";
            return String.format("'%s%s", str, termStr);
        } catch (DecoderException e) {
            return null;
        }
    }

    private String printCodepage(Long codepage) {
        String mapped = CODEPAGE_MAP.get(codepage);
        return codepage != null ? ",cp" + (mapped != null ? mapped : codepage) : "";
    }

    private String parseReadMemory(GdbMiLine res) {
        List<Object> memory = res.getResults().getValue("memory") != null ? res.getResults().getList("memory") : null;
        GdbMiResults tuple = ((memory != null) && (memory.size() > 0)) ? (GdbMiResults) memory.get(0) : null;
        return tuple != null ? tuple.getString("contents") : null;
    }

    private boolean isStructured(GdbVariableObject var) {
        return "{...}".equals(var.getValue());
    }

    private boolean isSet(GdbVariableObject var) {
        return StrUtil.startsWith(var.getType(), "<invalid type code");
    }

    private static boolean isDynamicArray(String type) {
        return (type != null) && type.contains("(*)[]");
    }

    private static boolean isString(String type) {
        return (type != null) && TYPES_STRING.contains(type.toUpperCase());
    }

    private boolean isPChar(String type) {
        return (type != null) && TYPES_PCHAR.contains(type.toUpperCase());
    }

    private boolean isSizeInBytes(String type) {
        return "UTF8STRING".equalsIgnoreCase(type);
    }

    private int getCharSize(Integer elemSize, String type) {
        String typeUC = type.toUpperCase();
        int charSize = ("UNICODESTRING".equals(typeUC) || "WIDESTRING".equals(typeUC)) ? 2 : 1;
        if ((elemSize != null) && (charSize != elemSize)) {
            LOG.info(String.format("DBG Error: character size not match. type: %s (%d), element size from header: %d", type, charSize, elemSize));
        }
        if ((elemSize != null) && ((elemSize == 1) || (elemSize == 2))) {
            return elemSize;
        } else {
            return charSize;
        }
    }

    private boolean hasCodepageInfo(String type) {
        return "ANSISTRING".equalsIgnoreCase(type);   // TODO: codepage present only since FPC 3.x
    }

    private boolean hasRefcountInfo(String type) {
        return TYPES_STRING.contains(type) && !"WIDESTRING".equalsIgnoreCase(type);
    }

    private boolean isValidAddress(String addressStr) {
        long address = addressStr != null ? decodeLong(addressStr, 0L) : 0;
        return address != 0;
    }

    private long decodeLong(String addressStr, Long defaultValue) {
        try {
            return Long.decode(addressStr);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void updateVariableObjectUI(GdbVariableObject var) {
        if (var.getCallback() != null) {
            var.getCallback().evaluated(new PascalDebuggerValue(var));
        }
    }

}
