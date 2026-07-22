package io.omnnu.finbot.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-034 architecture guard: feature-first packages, layer dependency direction, and duty placement.
 *
 * <p>Scans {@code services/backend} main/test Java sources without third-party architecture libraries.
 */
class PackageArchitectureTest {

    private static final String SETTINGS_FILE = "settings.gradle.kts";
    private static final String SERVICES = "services";
    private static final String BACKEND = "backend";

    private static final String ROOT_PACKAGE = "io.omnnu.finbot";
    private static final String DOMAIN_PREFIX = ROOT_PACKAGE + ".domain.";
    private static final String APPLICATION_PREFIX = ROOT_PACKAGE + ".application.";
    private static final String INFRASTRUCTURE_PREFIX = ROOT_PACKAGE + ".infrastructure.";
    private static final String API_PREFIX = ROOT_PACKAGE + ".api.";
    private static final String CONFIGURATION_PREFIX = ROOT_PACKAGE + ".configuration.";
    private static final String OPERATIONS_PREFIX = ROOT_PACKAGE + ".operations.";
    private static final String SECURITY_PREFIX = ROOT_PACKAGE + ".security.";

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_DECLARATION =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_DECLARATION =
            Pattern.compile(
                    "\\b(?:public\\s+)?(?:(?:final|abstract|sealed|non-sealed)\\s+)*(interface|class|record|enum)\\s+[A-Za-z_][A-Za-z0-9_]*\\b");

    private static final Pattern APPLICATION_FEATURE_ROOT =
            Pattern.compile("^io\\.omnnu\\.finbot\\.application\\.[^.]+$");
    private static final Pattern INFRASTRUCTURE_FEATURE_ROOT =
            Pattern.compile("^io\\.omnnu\\.finbot\\.infrastructure\\.[^.]+$");
    private static final Pattern APPLICATION_SERVICE_PACKAGE =
            Pattern.compile("^io\\.omnnu\\.finbot\\.application\\.[^.]+\\.service(?:\\..+)?$");
    private static final Pattern APPLICATION_USE_CASE_PACKAGE =
            Pattern.compile("^io\\.omnnu\\.finbot\\.application\\.[^.]+\\.port\\.in(?:\\..+)?$");
    private static final Pattern APPLICATION_PORT_OUT_PACKAGE =
            Pattern.compile("^io\\.omnnu\\.finbot\\.application\\.[^.]+\\.port\\.out(?:\\..+)?$");
    private static final Pattern APPLICATION_EXCEPTION_PACKAGE =
            Pattern.compile("^io\\.omnnu\\.finbot\\.application\\.[^.]+\\.exception(?:\\..+)?$");
    private static final Pattern API_CONTROLLER_PACKAGE =
            Pattern.compile("^io\\.omnnu\\.finbot\\.api\\.[^.]+\\.controller(?:\\..+)?$");
    private static final Pattern API_DTO_PACKAGE =
            Pattern.compile("^io\\.omnnu\\.finbot\\.api\\.[^.]+\\.dto(?:\\..+)?$");
    private static final Pattern INFRASTRUCTURE_PERSISTENCE_PACKAGE =
            Pattern.compile("^io\\.omnnu\\.finbot\\.infrastructure\\.[^.]+\\.persistence(?:\\..+)?$");

    @Test
    void packageArchitectureConformsToAdr034() throws IOException {
        Path backendRoot = locateBackendRoot();
        List<JavaSource> sources = scanJavaSources(backendRoot);
        if (sources.isEmpty()) {
            fail("No Java sources found under " + backendRoot);
        }

        List<String> violations = new ArrayList<>();
        for (JavaSource source : sources) {
            checkPathPackageConsistency(source, violations);
            checkNoGeneratedPackage(source, violations);
            if (source.mainSource()) {
                checkLayerDependencies(source, violations);
                checkApplicationFeatureLayout(source, violations);
                checkInfrastructureFeatureLayout(source, violations);
                checkApiPlacement(source, violations);
                checkBootstrapPlacement(source, violations);
            }
        }

        if (!violations.isEmpty()) {
            violations.sort(Comparator.naturalOrder());
            fail(
                    "Package architecture violations ("
                            + violations.size()
                            + "):\n"
                            + String.join("\n", violations));
        }
    }

    private static void checkPathPackageConsistency(JavaSource source, List<String> violations) {
        if (source.declaredPackage() == null) {
            violations.add(formatViolation(source, "missing package declaration"));
            return;
        }
        if (!source.declaredPackage().equals(source.pathPackage())) {
            violations.add(
                    formatViolation(
                            source,
                            "source path package '"
                                    + source.pathPackage()
                                    + "' does not match declared package '"
                                    + source.declaredPackage()
                                    + "'"));
        }
    }

    private static void checkNoGeneratedPackage(JavaSource source, List<String> violations) {
        String pkg = source.effectivePackage();
        if (pkg == null) {
            return;
        }
        for (String segment : pkg.split("\\.")) {
            if ("generated".equals(segment)) {
                violations.add(
                        formatViolation(
                                source, "generated package is forbidden (no code generation)"));
                return;
            }
        }
        Path relative = source.relativePath();
        for (Path part : relative) {
            if ("generated".equals(part.toString())) {
                violations.add(
                        formatViolation(
                                source, "generated source path segment is forbidden"));
                return;
            }
        }
    }

    private static void checkLayerDependencies(JavaSource source, List<String> violations) {
        String pkg = source.effectivePackage();
        if (pkg == null) {
            return;
        }

        Set<String> imports = source.imports();
        if (isUnder(pkg, DOMAIN_PREFIX) || pkg.equals(ROOT_PACKAGE + ".domain")) {
            for (String imported : imports) {
                if (isForbiddenDomainDependency(imported)) {
                    violations.add(
                            formatViolation(
                                    source,
                                    "domain must not depend on application/infrastructure/api/Spring/JDBC ("
                                            + imported
                                            + ")"));
                }
            }
            return;
        }

        if (isUnder(pkg, APPLICATION_PREFIX) || pkg.equals(ROOT_PACKAGE + ".application")) {
            for (String imported : imports) {
                if (isForbiddenApplicationDependency(imported)) {
                    violations.add(
                            formatViolation(
                                    source,
                                    "application must not depend on infrastructure/api/Spring/JDBC ("
                                            + imported
                                            + ")"));
                }
            }
            return;
        }

        if (isUnder(pkg, INFRASTRUCTURE_PREFIX) || pkg.equals(ROOT_PACKAGE + ".infrastructure")) {
            for (String imported : imports) {
                if (isForbiddenInfrastructureDependency(imported)) {
                    violations.add(
                            formatViolation(
                                    source,
                                    "infrastructure must not depend on api (" + imported + ")"));
                }
            }
        }
    }

    private static void checkApplicationFeatureLayout(JavaSource source, List<String> violations) {
        String pkg = source.effectivePackage();
        if (pkg == null || !isUnder(pkg, APPLICATION_PREFIX)) {
            return;
        }

        if (APPLICATION_FEATURE_ROOT.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source,
                            "application feature root must not contain types; place under service/dto/port/exception"));
        }

        String simpleName = source.simpleName();
        if (isApplicationServiceType(simpleName)
                && !APPLICATION_SERVICE_PACKAGE.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source,
                            "application service type must live in application.<feature>.service"));
        }
        if (simpleName.endsWith("UseCase") && !APPLICATION_USE_CASE_PACKAGE.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source,
                            "application *UseCase must live in application.<feature>.port.in"));
        }
        if (source.interfaceType()
                && isApplicationOutputPortType(simpleName)
                && !APPLICATION_PORT_OUT_PACKAGE.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source,
                            "application output port must live in application.<feature>.port.out"));
        }
        if (simpleName.endsWith("Exception")
                && !APPLICATION_EXCEPTION_PACKAGE.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source,
                            "application *Exception must live in application.<feature>.exception"));
        }
    }

    private static void checkInfrastructureFeatureLayout(JavaSource source, List<String> violations) {
        String pkg = source.effectivePackage();
        if (pkg == null || !isUnder(pkg, INFRASTRUCTURE_PREFIX)) {
            return;
        }

        if (INFRASTRUCTURE_FEATURE_ROOT.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source,
                            "infrastructure feature root must not contain types; place under persistence/client/adapter"));
        }

        String simpleName = source.simpleName();
        if (isJdbcOrPostgresType(simpleName)
                && !INFRASTRUCTURE_PERSISTENCE_PACKAGE.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source,
                            "infrastructure Jdbc/Postgres type must live in infrastructure.<feature>.persistence"));
        }
    }

    private static void checkApiPlacement(JavaSource source, List<String> violations) {
        String pkg = source.effectivePackage();
        if (pkg == null || !isUnder(pkg, API_PREFIX)) {
            return;
        }

        String simpleName = source.simpleName();
        if (simpleName.endsWith("Controller") && !API_CONTROLLER_PACKAGE.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source, "api *Controller must live in api.<feature>.controller"));
        }
        if ((simpleName.endsWith("Request")
                        || simpleName.endsWith("Response")
                        || simpleName.endsWith("Responses"))
                && !API_DTO_PACKAGE.matcher(pkg).matches()) {
            violations.add(
                    formatViolation(
                            source, "api *Request/*Response must live in api.<feature>.dto"));
        }
    }

    private static void checkBootstrapPlacement(JavaSource source, List<String> violations) {
        String pkg = source.effectivePackage();
        if (pkg == null) {
            return;
        }

        String simpleName = source.simpleName();
        if (isUnder(pkg, CONFIGURATION_PREFIX)) {
            if (simpleName.endsWith("Properties")
                    && !pkg.equals(ROOT_PACKAGE + ".configuration.properties")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap *Properties must live in configuration.properties"));
            }
            if (simpleName.endsWith("Configuration")
                    && !pkg.equals(ROOT_PACKAGE + ".configuration.wiring")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap *Configuration must live in configuration.wiring"));
            }
            if (pkg.equals(ROOT_PACKAGE + ".configuration")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap configuration root must not contain types"));
            }
            return;
        }

        if (isUnder(pkg, OPERATIONS_PREFIX)) {
            if (simpleName.endsWith("TaskHandler")
                    && !pkg.equals(ROOT_PACKAGE + ".operations.handler")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap *TaskHandler must live in operations.handler"));
            }
            if (endsWithAny(simpleName, "Runtime", "Runner", "Reconciler")
                    && !pkg.equals(ROOT_PACKAGE + ".operations.runtime")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap runtime type must live in operations.runtime"));
            }
            if (pkg.equals(ROOT_PACKAGE + ".operations")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap operations root must not contain types"));
            }
            return;
        }

        if (isUnder(pkg, SECURITY_PREFIX)) {
            if (simpleName.endsWith("Principal")
                    && !pkg.equals(ROOT_PACKAGE + ".security.principal")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap *Principal must live in security.principal"));
            }
            if (endsWithAny(simpleName, "Filter", "Interceptor")
                    && !pkg.equals(ROOT_PACKAGE + ".security.filter")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap filter/interceptor must live in security.filter"));
            }
            if (endsWithAny(simpleName, "Configuration", "RequestHandler")
                    && !pkg.equals(ROOT_PACKAGE + ".security.configuration")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap security configuration must live in security.configuration"));
            }
            if (pkg.equals(ROOT_PACKAGE + ".security")) {
                violations.add(
                        formatViolation(
                                source,
                                "bootstrap security root must not contain types"));
            }
        }
    }

    private static boolean isJdbcOrPostgresType(String simpleName) {
        return simpleName.contains("Jdbc") || simpleName.contains("Postgres");
    }

    private static boolean isApplicationServiceType(String simpleName) {
        return endsWithAny(
                simpleName,
                "Service",
                "Coordinator",
                "Manager",
                "Executor",
                "Collector",
                "Invoker",
                "Policy");
    }

    private static boolean isApplicationOutputPortType(String simpleName) {
        return endsWithAny(
                simpleName,
                "Repository",
                "Store",
                "Gateway",
                "Resolver",
                "Publisher",
                "Reader",
                "Stream",
                "Writer",
                "Encoder",
                "Verifier",
                "Cryptography",
                "Normalizer",
                "Builder",
                "Parser",
                "Factory",
                "Query",
                "Generator");
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForbiddenDomainDependency(String imported) {
        return startsWithTypeOrPackage(imported, APPLICATION_PREFIX)
                || imported.equals(ROOT_PACKAGE + ".application")
                || startsWithTypeOrPackage(imported, INFRASTRUCTURE_PREFIX)
                || imported.equals(ROOT_PACKAGE + ".infrastructure")
                || startsWithTypeOrPackage(imported, API_PREFIX)
                || imported.equals(ROOT_PACKAGE + ".api")
                || isSpringDependency(imported)
                || isJdbcDependency(imported);
    }

    private static boolean isForbiddenApplicationDependency(String imported) {
        return startsWithTypeOrPackage(imported, INFRASTRUCTURE_PREFIX)
                || imported.equals(ROOT_PACKAGE + ".infrastructure")
                || startsWithTypeOrPackage(imported, API_PREFIX)
                || imported.equals(ROOT_PACKAGE + ".api")
                || isSpringDependency(imported)
                || isJdbcDependency(imported);
    }

    private static boolean isForbiddenInfrastructureDependency(String imported) {
        return startsWithTypeOrPackage(imported, API_PREFIX) || imported.equals(ROOT_PACKAGE + ".api");
    }

    private static boolean isSpringDependency(String imported) {
        return imported.equals("org.springframework")
                || imported.startsWith("org.springframework.");
    }

    private static boolean isJdbcDependency(String imported) {
        return imported.equals("java.sql")
                || imported.startsWith("java.sql.")
                || imported.equals("javax.sql")
                || imported.startsWith("javax.sql.")
                || imported.equals("jakarta.sql")
                || imported.startsWith("jakarta.sql.")
                || imported.startsWith("org.springframework.jdbc")
                || imported.equals("org.postgresql")
                || imported.startsWith("org.postgresql.");
    }

    private static boolean startsWithTypeOrPackage(String imported, String prefix) {
        return imported.startsWith(prefix);
    }

    private static boolean isUnder(String packageName, String prefix) {
        return packageName.startsWith(prefix);
    }

    private static String formatViolation(JavaSource source, String rule) {
        return source.displayPath() + ": " + rule;
    }

    static Path locateBackendRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null) {
            Path nested = dir.resolve(SERVICES).resolve(BACKEND).resolve(SETTINGS_FILE);
            if (Files.isRegularFile(nested)) {
                return nested.getParent();
            }
            Path settings = dir.resolve(SETTINGS_FILE);
            if (Files.isRegularFile(settings) && isServicesBackendDirectory(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate services/backend/"
                        + SETTINGS_FILE
                        + " from working directory "
                        + System.getProperty("user.dir"));
    }

    private static boolean isServicesBackendDirectory(Path dir) {
        Path fileName = dir.getFileName();
        Path parent = dir.getParent();
        return fileName != null
                && BACKEND.equals(fileName.toString())
                && parent != null
                && parent.getFileName() != null
                && SERVICES.equals(parent.getFileName().toString());
    }

    private static List<JavaSource> scanJavaSources(Path backendRoot) throws IOException {
        List<JavaSource> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(backendRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> isMainOrTestJavaSource(backendRoot, path))
                    .forEach(
                            path -> {
                                try {
                                    sources.add(JavaSource.parse(backendRoot, path));
                                } catch (IOException ex) {
                                    throw new IllegalStateException("Failed to read " + path, ex);
                                }
                            });
        }
        sources.sort(Comparator.comparing(JavaSource::displayPath));
        return sources;
    }

    private static boolean isMainOrTestJavaSource(Path backendRoot, Path file) {
        Path relative = backendRoot.relativize(file.toAbsolutePath().normalize());
        boolean underBuild = false;
        for (Path part : relative) {
            String name = part.toString();
            if ("build".equals(name) || ".gradle".equals(name) || "out".equals(name)) {
                underBuild = true;
                break;
            }
        }
        if (underBuild) {
            return false;
        }
        return containsSourceRoot(relative, "main") || containsSourceRoot(relative, "test");
    }

    private static boolean containsSourceRoot(Path relative, String mainOrTest) {
        int count = relative.getNameCount();
        for (int i = 0; i + 2 < count; i++) {
            if ("src".equals(relative.getName(i).toString())
                    && mainOrTest.equals(relative.getName(i + 1).toString())
                    && "java".equals(relative.getName(i + 2).toString())) {
                return true;
            }
        }
        return false;
    }

    private record JavaSource(
            Path relativePath,
            String displayPath,
            boolean mainSource,
            String pathPackage,
            String declaredPackage,
            String simpleName,
            boolean interfaceType,
            Set<String> imports) {

        String effectivePackage() {
            return declaredPackage != null ? declaredPackage : pathPackage;
        }

        static JavaSource parse(Path backendRoot, Path absolutePath) throws IOException {
            Path normalized = absolutePath.toAbsolutePath().normalize();
            Path relative = backendRoot.relativize(normalized);
            String displayPath = toDisplayPath(relative);
            boolean mainSource = containsSourceRoot(relative, "main");
            String pathPackage = packageFromSourcePath(relative);
            String content = Files.readString(normalized, StandardCharsets.UTF_8);
            String declaredPackage = firstGroup(PACKAGE_DECLARATION, content);
            Set<String> imports = parseImports(content);
            String fileName = normalized.getFileName().toString();
            String simpleName =
                    fileName.endsWith(".java")
                            ? fileName.substring(0, fileName.length() - ".java".length())
                            : fileName;
            Matcher typeMatcher = TYPE_DECLARATION.matcher(content);
            boolean interfaceType = typeMatcher.find() && "interface".equals(typeMatcher.group(1));
            return new JavaSource(
                    relative,
                    displayPath,
                    mainSource,
                    pathPackage,
                    declaredPackage,
                    simpleName,
                    interfaceType,
                    imports);
        }

        private static Set<String> parseImports(String content) {
            Set<String> imports = new LinkedHashSet<>();
            Matcher matcher = IMPORT_DECLARATION.matcher(content);
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.endsWith(".*")) {
                    imports.add(imported.substring(0, imported.length() - 2));
                } else {
                    imports.add(imported);
                }
            }
            return imports;
        }

        private static String packageFromSourcePath(Path relative) {
            int count = relative.getNameCount();
            for (int i = 0; i + 2 < count; i++) {
                if ("src".equals(relative.getName(i).toString())
                        && ("main".equals(relative.getName(i + 1).toString())
                                || "test".equals(relative.getName(i + 1).toString()))
                        && "java".equals(relative.getName(i + 2).toString())) {
                    int start = i + 3;
                    int end = count - 1; // exclude file name
                    if (start >= end) {
                        return "";
                    }
                    StringBuilder pkg = new StringBuilder();
                    for (int j = start; j < end; j++) {
                        if (pkg.length() > 0) {
                            pkg.append('.');
                        }
                        pkg.append(relative.getName(j));
                    }
                    return pkg.toString();
                }
            }
            throw new IllegalStateException("Not a main/test Java source path: " + relative);
        }

        private static String toDisplayPath(Path relative) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < relative.getNameCount(); i++) {
                if (i > 0) {
                    builder.append('/');
                }
                builder.append(relative.getName(i));
            }
            return builder.toString();
        }

        private static String firstGroup(Pattern pattern, String content) {
            Matcher matcher = pattern.matcher(content);
            if (!matcher.find()) {
                return null;
            }
            return matcher.group(1);
        }
    }
}
