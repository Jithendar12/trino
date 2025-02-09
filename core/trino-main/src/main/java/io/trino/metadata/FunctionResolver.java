/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.metadata.FunctionBinder.CatalogFunctionBinding;
import io.trino.metadata.ResolvedFunction.ResolvedFunctionDecoder;
import io.trino.spi.TrinoException;
import io.trino.spi.TrinoWarning;
import io.trino.spi.connector.CatalogHandle;
import io.trino.spi.function.CatalogSchemaFunctionName;
import io.trino.spi.function.FunctionDependencyDeclaration;
import io.trino.spi.function.FunctionDependencyDeclaration.CastDependency;
import io.trino.spi.function.FunctionDependencyDeclaration.FunctionDependency;
import io.trino.spi.function.FunctionDependencyDeclaration.OperatorDependency;
import io.trino.spi.function.FunctionKind;
import io.trino.spi.function.FunctionMetadata;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeSignature;
import io.trino.sql.SqlPathElement;
import io.trino.sql.analyzer.TypeSignatureProvider;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.QualifiedName;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.trino.metadata.FunctionBinder.functionNotFound;
import static io.trino.metadata.GlobalFunctionCatalog.builtinFunctionName;
import static io.trino.metadata.SignatureBinder.applyBoundVariables;
import static io.trino.spi.StandardErrorCode.FUNCTION_NOT_FOUND;
import static io.trino.spi.StandardErrorCode.MISSING_CATALOG_NAME;
import static io.trino.spi.connector.StandardWarningCode.DEPRECATED_FUNCTION;
import static io.trino.spi.function.FunctionKind.AGGREGATE;
import static io.trino.spi.function.FunctionKind.WINDOW;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypeSignatures;
import static java.util.Objects.requireNonNull;

public class FunctionResolver
{
    private final Metadata metadata;
    private final TypeManager typeManager;
    private final WarningCollector warningCollector;
    private final ResolvedFunctionDecoder functionDecoder;
    private final FunctionBinder functionBinder;

    public FunctionResolver(Metadata metadata, TypeManager typeManager, ResolvedFunctionDecoder functionDecoder, WarningCollector warningCollector)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        this.functionDecoder = requireNonNull(functionDecoder, "functionDecoder is null");
        this.functionBinder = new FunctionBinder(metadata, typeManager);
    }

    /**
     * Is the named function an aggregation function?
     * This does not need type parameters because overloads between aggregation and other function types are not allowed.
     */
    public boolean isAggregationFunction(Session session, QualifiedName name)
    {
        return isFunctionKind(session, name, AGGREGATE);
    }

    public boolean isWindowFunction(Session session, QualifiedName name)
    {
        return isFunctionKind(session, name, WINDOW);
    }

    private boolean isFunctionKind(Session session, QualifiedName name, FunctionKind functionKind)
    {
        Optional<ResolvedFunction> resolvedFunction = functionDecoder.fromQualifiedName(name);
        if (resolvedFunction.isPresent()) {
            return resolvedFunction.get().getFunctionKind() == functionKind;
        }

        for (CatalogSchemaFunctionName catalogSchemaFunctionName : toPath(session, name)) {
            Collection<CatalogFunctionMetadata> candidates = metadata.getFunctions(session, catalogSchemaFunctionName);
            if (!candidates.isEmpty()) {
                return candidates.stream()
                        .map(CatalogFunctionMetadata::functionMetadata)
                        .map(FunctionMetadata::getKind)
                        .anyMatch(functionKind::equals);
            }
        }
        return false;
    }

    public ResolvedFunction resolveFunction(Session session, QualifiedName name, List<TypeSignatureProvider> parameterTypes)
    {
        Optional<ResolvedFunction> resolvedFunction = functionDecoder.fromQualifiedName(name);
        if (resolvedFunction.isPresent()) {
            return resolvedFunction.get();
        }

        CatalogFunctionBinding catalogFunctionBinding = bindFunction(
                session,
                name,
                parameterTypes,
                catalogSchemaFunctionName -> metadata.getFunctions(session, catalogSchemaFunctionName));

        FunctionMetadata functionMetadata = catalogFunctionBinding.functionMetadata();
        if (functionMetadata.isDeprecated()) {
            warningCollector.add(new TrinoWarning(DEPRECATED_FUNCTION, "Use of deprecated function: %s: %s".formatted(name, functionMetadata.getDescription())));
        }

        return resolve(session, catalogFunctionBinding);
    }

    private ResolvedFunction resolve(Session session, CatalogFunctionBinding functionBinding)
    {
        FunctionDependencyDeclaration dependencies = metadata.getFunctionDependencies(
                session,
                functionBinding.catalogHandle(),
                functionBinding.functionBinding().getFunctionId(),
                functionBinding.functionBinding().getBoundSignature());

        return resolveFunctionBinding(
                metadata,
                typeManager,
                functionBinder,
                functionDecoder,
                functionBinding.catalogHandle(),
                functionBinding.functionBinding(),
                functionBinding.functionMetadata(),
                dependencies,
                catalogSchemaFunctionName -> metadata.getFunctions(session, catalogSchemaFunctionName),
                catalogFunctionBinding -> resolve(session, catalogFunctionBinding));
    }

    private CatalogFunctionBinding bindFunction(
            Session session,
            QualifiedName name,
            List<TypeSignatureProvider> parameterTypes,
            Function<CatalogSchemaFunctionName, Collection<CatalogFunctionMetadata>> candidateLoader)
    {
        ImmutableList.Builder<CatalogFunctionMetadata> allCandidates = ImmutableList.builder();
        for (CatalogSchemaFunctionName catalogSchemaFunctionName : toPath(session, name)) {
            Collection<CatalogFunctionMetadata> candidates = candidateLoader.apply(catalogSchemaFunctionName);
            Optional<CatalogFunctionBinding> match = functionBinder.tryBindFunction(parameterTypes, candidates);
            if (match.isPresent()) {
                return match.get();
            }
            allCandidates.addAll(candidates);
        }

        List<CatalogFunctionMetadata> candidates = allCandidates.build();
        throw functionNotFound(name.toString(), parameterTypes, candidates);
    }

    static ResolvedFunction resolveFunctionBinding(
            Metadata metadata,
            TypeManager typeManager,
            FunctionBinder functionBinder,
            ResolvedFunctionDecoder functionDecoder,
            CatalogHandle catalogHandle,
            FunctionBinding functionBinding,
            FunctionMetadata functionMetadata,
            FunctionDependencyDeclaration dependencies,
            Function<CatalogSchemaFunctionName, Collection<CatalogFunctionMetadata>> candidateLoader,
            Function<CatalogFunctionBinding, ResolvedFunction> resolver)
    {
        Map<TypeSignature, Type> dependentTypes = dependencies.getTypeDependencies().stream()
                .map(typeSignature -> applyBoundVariables(typeSignature, functionBinding))
                .collect(toImmutableMap(Function.identity(), typeManager::getType, (left, right) -> left));

        ImmutableSet.Builder<ResolvedFunction> functions = ImmutableSet.builder();
        for (FunctionDependency functionDependency : dependencies.getFunctionDependencies()) {
            try {
                CatalogSchemaFunctionName name = functionDependency.getName();
                Optional<ResolvedFunction> resolvedFunction = functionDecoder.fromCatalogSchemaFunctionName(name);
                if (resolvedFunction.isPresent()) {
                    functions.add(resolvedFunction.get());
                }
                else {
                    CatalogFunctionBinding catalogFunctionBinding = functionBinder.bindFunction(
                            fromTypeSignatures(applyBoundVariables(functionDependency.getArgumentTypes(), functionBinding)),
                            candidateLoader.apply(name),
                            name.toString());
                    functions.add(resolver.apply(catalogFunctionBinding));
                }
            }
            catch (TrinoException e) {
                if (!functionDependency.isOptional()) {
                    throw e;
                }
            }
        }
        for (OperatorDependency operatorDependency : dependencies.getOperatorDependencies()) {
            try {
                List<Type> argumentTypes = applyBoundVariables(operatorDependency.getArgumentTypes(), functionBinding).stream()
                        .map(typeManager::getType)
                        .collect(toImmutableList());
                functions.add(metadata.resolveOperator(operatorDependency.getOperatorType(), argumentTypes));
            }
            catch (TrinoException e) {
                if (!operatorDependency.isOptional()) {
                    throw e;
                }
            }
        }
        for (CastDependency castDependency : dependencies.getCastDependencies()) {
            try {
                Type fromType = typeManager.getType(applyBoundVariables(castDependency.getFromType(), functionBinding));
                Type toType = typeManager.getType(applyBoundVariables(castDependency.getToType(), functionBinding));
                functions.add(metadata.getCoercion(fromType, toType));
            }
            catch (TrinoException e) {
                if (!castDependency.isOptional()) {
                    throw e;
                }
            }
        }

        return new ResolvedFunction(
                functionBinding.getBoundSignature(),
                catalogHandle,
                functionBinding.getFunctionId(),
                functionMetadata.getKind(),
                functionMetadata.isDeterministic(),
                functionMetadata.getFunctionNullability(),
                dependentTypes,
                functions.build());
    }

    // this is visible for the table function resolution, which should be merged into this class
    public static List<CatalogSchemaFunctionName> toPath(Session session, QualifiedName name)
    {
        List<String> parts = name.getParts();
        if (parts.size() > 3) {
            throw new TrinoException(FUNCTION_NOT_FOUND, "Invalid function name: " + name);
        }
        if (parts.size() == 3) {
            return ImmutableList.of(new CatalogSchemaFunctionName(parts.get(0), parts.get(1), parts.get(2)));
        }

        if (parts.size() == 2) {
            String currentCatalog = session.getCatalog()
                    .orElseThrow(() -> new TrinoException(MISSING_CATALOG_NAME, "Session default catalog must be set to resolve a partial function name: " + name));
            return ImmutableList.of(new CatalogSchemaFunctionName(currentCatalog, parts.get(0), parts.get(1)));
        }

        ImmutableList.Builder<CatalogSchemaFunctionName> names = ImmutableList.builder();

        // global namespace
        names.add(builtinFunctionName(parts.get(0)));

        // add resolved path items
        for (SqlPathElement sqlPathElement : session.getPath().getParsedPath()) {
            String catalog = sqlPathElement.getCatalog().map(Identifier::getCanonicalValue).or(session::getCatalog)
                    .orElseThrow(() -> new TrinoException(MISSING_CATALOG_NAME, "Session default catalog must be set to resolve a partial function name: " + name));
            names.add(new CatalogSchemaFunctionName(catalog, sqlPathElement.getSchema().getCanonicalValue(), parts.get(0)));
        }
        return names.build();
    }
}
