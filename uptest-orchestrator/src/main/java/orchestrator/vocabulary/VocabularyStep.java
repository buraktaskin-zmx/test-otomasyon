package orchestrator.vocabulary;

import java.util.List;

/**
 * One step definition as declared by an executor: the Cucumber expression, the
 * annotation keyword, and the placeholder types in order.
 *
 * Mirrors an entry of the executor's generated vocabulary.json. Nothing here is
 * hand-maintained.
 */
public record VocabularyStep(
        String pattern,
        String keyword,
        List<String> parameters,
        String className,
        String method
) {
    /** The pattern with placeholders removed, for fuzzy "did you mean" ranking. */
    public String skeleton() {
        return pattern.replaceAll("\\{\\w*}", "").replaceAll("\\s+", " ").trim();
    }
}
