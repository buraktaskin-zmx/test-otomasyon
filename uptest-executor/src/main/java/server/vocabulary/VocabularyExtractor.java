package server.vocabulary;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Build-time generator for {@code vocabulary.json}: the single source of truth
 * for which Gherkin steps exist.
 *
 * Run from the build (exec-maven-plugin, process-classes) over the module's own
 * compiled classes, so the JSON ships inside the executor jar and cannot drift
 * from the step definitions it describes. The orchestrator serves it to the
 * validator and to the LLM prompt; VOCABULARY.md is the human-facing view of
 * the same data. A third hand-maintained copy guarantees drift — this file
 * exists so there isn't one.
 *
 * Reading annotations rather than parsing source is safe here because the
 * Cucumber annotations are {@code RetentionPolicy.RUNTIME}.
 *
 * Usage: VocabularyExtractor <classesDir> <outputJson>
 */
public final class VocabularyExtractor {

    /** Covers io.cucumber.java.en.Given, .When, .Then, .And, .But — and the
     *  localised variants (io.cucumber.java.tr.*) if steps are ever written in
     *  Turkish keywords. */
    private static final String CUCUMBER_ANNOTATION_PACKAGE = "io.cucumber.java.";

    private static final Pattern PARAMETER = Pattern.compile("\\{(\\w*)}");

    private VocabularyExtractor() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: VocabularyExtractor <classesDir> <outputJson>");
        }
        Path classesDir = Path.of(args[0]);
        Path output = Path.of(args[1]);

        List<Map<String, Object>> steps = extract(classesDir);

        Files.createDirectories(output.getParent());
        Files.writeString(output, toJson(steps), StandardCharsets.UTF_8);

        System.out.println("[vocabulary] " + steps.size() + " steps -> " + output);
    }

    /**
     * Hand-written rather than Jackson: this runs during the build, and a build
     * tool should not break when the application's JSON library changes major
     * version (Spring Boot 4 moved to Jackson 3 / tools.jackson.*).
     */
    private static String toJson(List<Map<String, Object>> steps) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"stepCount\": ").append(steps.size()).append(",\n  \"steps\": [\n");
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            json.append("    {\n");
            json.append("      \"pattern\": ").append(quote((String) step.get("pattern"))).append(",\n");
            json.append("      \"keyword\": ").append(quote((String) step.get("keyword"))).append(",\n");
            json.append("      \"parameters\": [");
            @SuppressWarnings("unchecked")
            List<String> parameters = (List<String>) step.get("parameters");
            for (int p = 0; p < parameters.size(); p++) {
                if (p > 0) {
                    json.append(", ");
                }
                json.append(quote(parameters.get(p)));
            }
            json.append("],\n");
            json.append("      \"className\": ").append(quote((String) step.get("className"))).append(",\n");
            json.append("      \"method\": ").append(quote((String) step.get("method"))).append("\n");
            json.append("    }").append(i < steps.size() - 1 ? "," : "").append("\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    static List<Map<String, Object>> extract(Path classesDir) throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        try (Stream<Path> files = Files.walk(classesDir)) {
            List<Path> classFiles = files
                    .filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .toList();

            for (Path classFile : classFiles) {
                String className = toClassName(classesDir, classFile);
                Class<?> type;
                try {
                    // initialize=false: never run static initialisers of page
                    // objects during a build.
                    type = Class.forName(className, false, loader);
                } catch (Throwable ignored) {
                    // A class we cannot load holds no step definitions we could
                    // have used anyway.
                    continue;
                }
                collectFrom(type, steps);
            }
        }

        steps.sort(Comparator.comparing(s -> (String) s.get("pattern")));
        return steps;
    }

    private static void collectFrom(Class<?> type, List<Map<String, Object>> steps) {
        Method[] methods;
        try {
            methods = type.getDeclaredMethods();
        } catch (Throwable ignored) {
            return;
        }

        for (Method method : methods) {
            for (Annotation annotation : method.getAnnotations()) {
                String annotationType = annotation.annotationType().getName();
                if (!annotationType.startsWith(CUCUMBER_ANNOTATION_PACKAGE)) {
                    continue;
                }
                String expression = valueOf(annotation);
                if (expression == null || expression.isBlank()) {
                    continue;
                }
                steps.add(describe(expression, annotation, method));
            }
        }
    }

    private static Map<String, Object> describe(String expression, Annotation annotation, Method method) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("pattern", expression);
        step.put("keyword", simpleName(annotation.annotationType().getName()));
        step.put("parameters", parametersOf(expression));
        step.put("className", method.getDeclaringClass().getName());
        step.put("method", method.getName());
        return step;
    }

    /** The placeholder types in order, e.g. ["string", "string"] for
     *  "enter amount {string} {string}". Feeds the validator's argument checks
     *  and the LLM prompt's format rules. */
    private static List<String> parametersOf(String expression) {
        List<String> parameters = new ArrayList<>();
        Matcher matcher = PARAMETER.matcher(expression);
        while (matcher.find()) {
            String name = matcher.group(1);
            parameters.add(name.isEmpty() ? "anonymous" : name);
        }
        return parameters;
    }

    private static String valueOf(Annotation annotation) {
        try {
            Object value = annotation.annotationType().getMethod("value").invoke(annotation);
            return (value instanceof String s) ? s : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative
                .substring(0, relative.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
    }
}
