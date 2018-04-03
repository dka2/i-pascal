package com.siberika.idea.pascal.lang.references;

import com.intellij.extapi.psi.StubBasedPsiElementBase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.StubBasedPsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import com.intellij.util.SmartList;
import com.intellij.util.containers.SmartHashSet;
import com.siberika.idea.pascal.PascalRTException;
import com.siberika.idea.pascal.lang.parser.NamespaceRec;
import com.siberika.idea.pascal.lang.parser.PascalParserUtil;
import com.siberika.idea.pascal.lang.psi.PasArrayType;
import com.siberika.idea.pascal.lang.psi.PasEntityScope;
import com.siberika.idea.pascal.lang.psi.PasGenericTypeIdent;
import com.siberika.idea.pascal.lang.psi.PasInvalidScopeException;
import com.siberika.idea.pascal.lang.psi.PasPointerType;
import com.siberika.idea.pascal.lang.psi.PasProcedureType;
import com.siberika.idea.pascal.lang.psi.PasSetType;
import com.siberika.idea.pascal.lang.psi.PasStringType;
import com.siberika.idea.pascal.lang.psi.PasTypeDecl;
import com.siberika.idea.pascal.lang.psi.PasTypeID;
import com.siberika.idea.pascal.lang.psi.PascalExportedRoutine;
import com.siberika.idea.pascal.lang.psi.PascalIdentDecl;
import com.siberika.idea.pascal.lang.psi.PascalModule;
import com.siberika.idea.pascal.lang.psi.PascalNamedElement;
import com.siberika.idea.pascal.lang.psi.PascalRoutine;
import com.siberika.idea.pascal.lang.psi.PascalStructType;
import com.siberika.idea.pascal.lang.psi.impl.PasArrayTypeImpl;
import com.siberika.idea.pascal.lang.psi.impl.PasClassTypeTypeDeclImpl;
import com.siberika.idea.pascal.lang.psi.impl.PasEnumTypeImpl;
import com.siberika.idea.pascal.lang.psi.impl.PasField;
import com.siberika.idea.pascal.lang.psi.impl.PasFileTypeImpl;
import com.siberika.idea.pascal.lang.psi.impl.PasSubRangeTypeImpl;
import com.siberika.idea.pascal.lang.psi.impl.PasVariantScope;
import com.siberika.idea.pascal.lang.stub.PasExportedRoutineStub;
import com.siberika.idea.pascal.lang.stub.PasIdentStub;
import com.siberika.idea.pascal.lang.stub.PasModuleStub;
import com.siberika.idea.pascal.lang.stub.PasNamedStub;
import com.siberika.idea.pascal.lang.stub.PascalModuleIndex;
import com.siberika.idea.pascal.sdk.BuiltinsParser;
import com.siberika.idea.pascal.util.PsiUtil;
import com.siberika.idea.pascal.util.SyncUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class ResolveUtil {

    private static final Logger LOG = Logger.getInstance(ResolveUtil.class);
    
    public static final String STRUCT_SUFFIX = "#";

    private static final Set<PasField.Kind> KINDS_FOLLOW_TYPE = EnumSet.of(PasField.Kind.TYPEREF, PasField.Kind.ARRAY, PasField.Kind.POINTER, PasField.Kind.CLASSREF, PasField.Kind.PROCEDURE);

    // Find Pascal modules by key using stub index. If key is not specified return all modules.
    @NotNull
    public static Collection<PascalModule> findUnitsWithStub(@NotNull Project project, @Nullable final Module module, @Nullable String key) {
        final Collection<PascalModule> modules = new SmartHashSet<>();
        final GlobalSearchScope scope = module != null ? GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false) : ProjectScope.getAllScope(project);
        if (key != null) {
            modules.addAll(StubIndex.getElements(PascalModuleIndex.KEY, key.toUpperCase(), project, scope, PascalModule.class));
        } else {
            Processor<String> processor = new Processor<String>() {
                @Override
                public boolean process(String key) {
                    modules.addAll(StubIndex.getElements(PascalModuleIndex.KEY, key.toUpperCase(), project, scope, PascalModule.class));
                    return true;
                }
            };
            StubIndex.getInstance().processAllKeys(PascalModuleIndex.KEY, processor, scope, null);
        }
        if ((null == key) || BuiltinsParser.UNIT_NAME_BUILTINS.equalsIgnoreCase(key)) {
            modules.add(BuiltinsParser.getBuiltinsModule(project));
        }
        return modules;
    }

    // retrieves type name and kind. Doesn't follow declaration recursively.
    @Nullable
    public static Pair<String, PasField.Kind> retrieveDeclarationType(@NotNull PascalNamedElement el) {
        PasTypeDecl typeDecl = null;
        PasTypeID typeId = null;
        if (PsiUtil.isVariableDecl(el) || PsiUtil.isFieldDecl(el) || PsiUtil.isPropertyDecl(el) || PsiUtil.isConstDecl(el)) {   // variable declaration case
            typeDecl = PsiTreeUtil.getNextSiblingOfType(el, PasTypeDecl.class);
            if (null == typeDecl) {
                typeId = PsiTreeUtil.getNextSiblingOfType(el, PasTypeID.class);
            }
        } else if (el.getParent() instanceof PasGenericTypeIdent) {                                                             // type declaration case
            el = (PascalNamedElement) el.getParent();
            typeDecl = PsiUtil.getTypeDeclaration(el);
        } else if (el.getParent() instanceof PascalRoutine) {                                                                   // routine declaration case
            typeId = ((PascalRoutine) el.getParent()).getFunctionTypeIdent();
        }
        return retrieveType(typeDecl, typeId);
    }

    private static Pair<String, PasField.Kind> retrieveType(PasTypeDecl typeDecl, PasTypeID typeId) {
        if ((null == typeId) && (typeDecl != null)) {
            typeId = typeDecl.getTypeID();
        }
        if (typeId != null) {
            return Pair.create(typeId.getFullyQualifiedIdent().getName(), PasField.Kind.TYPEREF);
        } else if (typeDecl != null) {
            return retrieveAnonymousType(typeDecl);
        } else {
            return null;
        }
    }

    private static Pair<String, PasField.Kind> retrieveAnonymousType(PasTypeDecl typeDecl) {
        PsiElement type = typeDecl.getFirstChild();
        if (type.getClass() == PasClassTypeTypeDeclImpl.class) {
            return Pair.create(((PasClassTypeTypeDeclImpl) type).getTypeID().getFullyQualifiedIdent().getName(), PasField.Kind.CLASSREF);
        } else if (type instanceof PascalStructType) {
            return Pair.create(null, PasField.Kind.STRUCT);
        } else if (type instanceof PasArrayType) {
            Pair<String, PasField.Kind> baseType = retrieveType(((PasArrayTypeImpl) type).getTypeDecl(), null);
            if (baseType != null) {
                return Pair.create(baseType.first, baseType.second != PasField.Kind.STRUCT ? PasField.Kind.ARRAY : PasField.Kind.STRUCT);
            } else {
                return Pair.create(null, PasField.Kind.ARRAY);
            }
        } else if (type instanceof PasSetType) {
            Pair<String, PasField.Kind> baseType = retrieveType(((PasSetType) type).getTypeDecl(), null);
            return Pair.create(baseType != null ? baseType.first : null, PasField.Kind.SET);
        } else if (type.getClass() == PasFileTypeImpl.class) {
            return Pair.create(null, PasField.Kind.STRUCT);
        } else if (type instanceof PasPointerType) {
            Pair<String, PasField.Kind> baseType = retrieveType(((PasPointerType) type).getTypeDecl(), null);
            if (baseType != null) {
                return Pair.create(baseType.first, baseType.second != PasField.Kind.STRUCT ? PasField.Kind.POINTER : PasField.Kind.STRUCT);
            } else {
                return Pair.create(null, PasField.Kind.POINTER);
            }
        } else if (type instanceof PasProcedureType) {
            Pair<String, PasField.Kind> baseType = retrieveType(((PasProcedureType) type).getTypeDecl(), null);
            return Pair.create(baseType != null ? baseType.first : null, PasField.Kind.PROCEDURE);
        } else if (type instanceof PasStringType) {
            return Pair.create(null, PasField.Kind.STRING);
        } else if (type.getClass() == PasEnumTypeImpl.class) {
            return Pair.create(null, PasField.Kind.ENUM);
        } else if (type.getClass() == PasSubRangeTypeImpl.class) {
            return Pair.create(null, PasField.Kind.SUBRANGE);
        }
        return null;
    }

    @Nullable
    public static PasEntityScope retrieveFieldTypeScope(@NotNull PasField field, ResolveContext context, int recursionCount) {
        if (SyncUtil.tryLockQuiet(field.getTypeLock(), SyncUtil.LOCK_TIMEOUT_MS)) {
            try {
                if (!field.isTypeResolved()) {
                    PascalNamedElement el = field.getElement();
                    if ((el instanceof StubBasedPsiElementBase) && (((StubBasedPsiElementBase) el).getStub() != null)) {
                        PasField.ValueType valueType = resolveTypeWithStub((StubBasedPsiElementBase) el, context, recursionCount);
                        if (valueType != null) {
                            valueType.field = field;
                            field.setValueType(valueType);
                        }
                    }
                }
                if (field.getValueType() == PasField.VARIANT) {
                    return new PasVariantScope(field.getElement());
                }
                return field.getValueType() != null ? field.getValueType().getTypeScopeStub() : null;
            } finally {
                field.getTypeLock().unlock();
            }
        } else {
            return null;
        }
    }

    public static PasField.ValueType resolveTypeWithStub(StubBasedPsiElementBase element, ResolveContext context, int recursionCount) {
        if (recursionCount == 1) {
            LOG.info("Resolving value type for " + element.getName());
        }
        if (element instanceof PascalIdentDecl) {
            return resolveIdentDeclType((PascalIdentDecl) element, context, recursionCount);
        } if (element instanceof PascalExportedRoutine) {
            return resolveRoutineType((PascalExportedRoutine) element, context, recursionCount);
        }
        return null;
    }

    private static PasField.ValueType resolveRoutineType(PascalExportedRoutine element, ResolveContext context, int recursionCount) {
        PasExportedRoutineStub stub = element.getStub();
        PsiElement scope = stub.getParentStub().getPsi();
        if (!(scope instanceof PasEntityScope)) {
            return null;
        }
        String type = element.getFunctionTypeStr();
        return resolveTypeForStub(type, element, new ResolveContext((PasEntityScope) scope, PasField.TYPES_TYPE, context.includeLibrary, null), recursionCount);
    }

    private static PasField.ValueType resolveIdentDeclType(@NotNull PascalIdentDecl element, ResolveContext context, int recursionCount) {
        PasIdentStub stub = element.getStub();
        PsiElement scope = stub.getParentStub().getPsi();
        if (!(scope instanceof PasEntityScope)) {
            return null;
        }
        ResolveContext typeResolveContext = new ResolveContext((PasEntityScope) scope, PasField.TYPES_TYPE, context.includeLibrary, null);
        String type = element.getTypeString();
        if ((type != null) && KINDS_FOLLOW_TYPE.contains(element.getTypeKind())) {
            return resolveTypeForStub(type, element, typeResolveContext, recursionCount);
        } else if (element.getTypeKind() == PasField.Kind.STRUCT) {
            return retrieveStruct(element.getName(), element, typeResolveContext, ++recursionCount);
        }
        return null;
    }

    private static PasField.ValueType resolveTypeForStub(String type, PsiElement element, ResolveContext context, int recursionCount) {
        Collection<PasField> types = resolveWithStubs(NamespaceRec.fromFQN(element, type), context, ++recursionCount);
        Iterator<PasField> iterator = types.iterator();
        if (iterator.hasNext()) {
            PasField pasField = iterator.next();
            PascalNamedElement el = pasField.getElement();
            return el instanceof PascalIdentDecl ? resolveIdentDeclType((PascalIdentDecl) el, context, recursionCount) : null;
        }
        return null;
    }

    private static PasField.ValueType retrieveStruct(String type, PascalIdentDecl element, ResolveContext context, int recursionCount) {
        Collection<PasField> structs = resolveWithStubs(NamespaceRec.fromFQN(element, type + STRUCT_SUFFIX), context, recursionCount);
        for (PasField struct : structs) {
            PascalNamedElement st = struct.getElement();
            if (st instanceof PasEntityScope) {
                LOG.info("===*** resolved value type " + type + " to a structure via stubs");
                return new PasField.ValueType(null, PasField.Kind.STRUCT, null, st);
            }
        }
        return null;
    }

    /**
     *  for each entry in FQN before target:
     *    find entity corresponding to NS in current scope
     *    if the entity represents a namespace - retrieve and make current
     *  for namespace of target entry add all its entities
     */
    public static Collection<PasField> resolveWithStubs(final NamespaceRec fqn, ResolveContext context, final int recursionCount) {
        assert(context.scope instanceof StubBasedPsiElement);
        StubElement stub = ((StubBasedPsiElement) context.scope).getStub();
        assert(stub instanceof PasNamedStub);
        PasEntityScope scope = context.scope;
        ProgressManager.checkCanceled();
        if (recursionCount > PasReferenceUtil.MAX_RECURSION_COUNT) {
            throw new PascalRTException("Too much recursion during resolving identifier: " + fqn.getParentIdent());
        }

        // First entry in FQN
        List<PasEntityScope> namespaces = new SmartList<PasEntityScope>();
        Collection<PasField> result = new HashSet<PasField>();

        Set<PasField.FieldType> fieldTypes = EnumSet.copyOf(context.fieldTypes);

        try {
            // Retrieve all namespaces affecting first FQN level
            while (scope != null) {
                addFirstNamespaces(namespaces, scope, context.includeLibrary);
                stub = stub.getParentStub();
                PsiElement parentScope = stub.getPsi();
                scope = fqn.isFirst() && (parentScope instanceof PasEntityScope) ? (PasEntityScope) parentScope : null;
            }

            List<PasEntityScope> newNs = PasReferenceUtil.checkUnitScope(result, namespaces, fqn);
            if (newNs != null) {
                namespaces = newNs;
                fieldTypes.remove(PasField.FieldType.UNIT);                                                              // Unit qualifier can be only first
            }

            while (fqn.isBeforeTarget() && (namespaces != null)) {
                PasField field = null;
                // Scan namespaces and get one matching field
                for (PasEntityScope namespace : namespaces) {
                    field = namespace.getField(fqn.getCurrentName());        // TODO: optimize?
                    if (field != null) {
                        break;
                    }
                }
                namespaces = null;
                if (field != null) {
                    PasEntityScope newNS;
                        newNS = retrieveFieldTypeScope(field, context, recursionCount);
                        boolean isDefault = "DEFAULT".equals(fqn.getLastName().toUpperCase());
                        if ((fqn.getRestLevels() == 1) && ((null == newNs) || isDefault)         // "default" type pseudo value
                                && (field.fieldType == PasField.FieldType.TYPE)) {                      // Enumerated type member
                            if (isDefault) {
                                fqn.next();
                                PasField defaultField = new PasField(field.owner, field.getElement(), "default", PasField.FieldType.CONSTANT, field.visibility);
                                if (PasReferenceUtil.isFieldMatches(defaultField, fqn, fieldTypes)) {
                                    result.add(defaultField);
                                }
//                                saveScope(context.resultScope, newNS, true);
                                return result;
                            }
                            /*if (field.getValueType() != null) {
                                SmartPsiElementPointer<PasTypeDecl> typePtr = field.getValueType().declaration;
                                PasTypeDecl enumType = typePtr != null ? typePtr.getElement() : null;
                                PasEnumType enumDecl = enumType != null ? _PsiTreeUtil.findChildOfType(enumType, PasEnumType.class) : null;
                                if (enumDecl != null) {
                                    fqn.next();
//                                    saveScope(context.resultScope, enumDecl, true);
                                    return collectEnumFields(result, field, enumDecl, fqn, fieldTypes);
                                }
                            }*/
                    }

                    namespaces = newNS != null ? new SmartList<PasEntityScope>(newNS) : null;
                    if (newNS instanceof PascalStructType) {
                        addParentNamespaces(namespaces, (PascalStructType) newNS);
                    }
                }
                fqn.next();
                fieldTypes.remove(PasField.FieldType.UNIT);                                                              // Unit qualifier can be only first in FQN
                fieldTypes.remove(PasField.FieldType.PSEUDO_VARIABLE);                                                   // Pseudo variables can be only first in FQN
            }

            if (!fqn.isComplete() && (namespaces != null)) {
                for (PasEntityScope namespace : namespaces) {
                    if (null == namespace) {
                        LOG.info(String.format("===*** null namespace! %s", fqn));
                        continue;
                    }
                    for (PasField pasField : namespace.getAllFields()) {
                        if (PasReferenceUtil.isFieldMatches(pasField, fqn, fieldTypes) &&
                                !result.contains(pasField) &&
                                isVisibleWithinUnit(pasField, fqn)) {
//                            saveScope(context.resultScope, namespace, false);
                            result.add(pasField);
                            if (!PasReferenceUtil.isCollectingAll(fqn)) {
                                break;
                            }
                        }
                    }
                    if (!result.isEmpty() && !PasReferenceUtil.isCollectingAll(fqn)) {
                        break;
                    }
                }
                if (result.isEmpty() && (context.resultScope != null)) {
                    context.resultScope.clear();
                    context.resultScope.addAll(namespaces);
                }
            }
        } catch (PasInvalidScopeException e) {
            /*if (namespaces != null) {
                for (PasEntityScope namespace : namespaces) {
                    namespace.invalidateCache();
                }
            }*/
        /*} catch (Throwable e) {
            //LOG.error(String.format("Error parsing scope %s, file %s", scope, scope != null ? scope.getContainingFile().getName() : ""), e);
            throw e;*/
        }
        return result;
    }

    private static boolean isVisibleWithinUnit(PasField field, NamespaceRec fqn) {
        return fqn.isIgnoreVisibility() || (field.fieldType == PasField.FieldType.ROUTINE) || PasField.isAllowed(field.visibility, PasField.Visibility.STRICT_PROTECTED);
    }

    private static void addFirstNamespaces(List<PasEntityScope> namespaces, PasEntityScope scope, boolean includeLibrary) {
        namespaces.add(scope);
        if (scope instanceof PascalModule) {
            addUnitNamespaces(namespaces, (PascalModule) scope);
        }
        if (scope instanceof PascalStructType) {
            addParentNamespaces(namespaces, (PascalStructType) scope);
        }
    }

    private static void addUnitNamespaces(List<PasEntityScope> namespaces, PascalModule scope) {
        PasModuleStub stub = scope.getStub();
        if (null == stub) {
            LOG.info("Stub is null" + scope);
            return;
        }
        for (String unitName : stub.getUsedUnitsPublic()) {
            namespaces.addAll(findUnitsWithStub(scope.getProject(), null, unitName));
        }
        for (String unitName : PascalParserUtil.EXPLICIT_UNITS) {
            namespaces.addAll(findUnitsWithStub(scope.getProject(), null, unitName));
        }
    }

    private static void addParentNamespaces(@Nullable List<PasEntityScope> namespaces, @NotNull PascalStructType scope) {
        if ((null == namespaces) || (namespaces.size() > PasReferenceUtil.MAX_NAMESPACES)) {
            return;
        }
        namespaces.addAll(PsiUtil.extractSmartPointers(scope.getParentScope()));
    }

    public static boolean isStubPowered(PascalNamedElement element) {
        return (element instanceof StubBasedPsiElementBase) && (((StubBasedPsiElementBase) element).getStub() != null);
    }

    public static String cleanupName(String name) {
        return name != null ? name.replaceAll(STRUCT_SUFFIX, "") : null;
    }
}
