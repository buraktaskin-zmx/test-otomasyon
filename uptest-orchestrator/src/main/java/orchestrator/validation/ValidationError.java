package orchestrator.validation;

import java.util.List;

/**
 * One reason a scenario would not run, addressed to an analyst rather than a
 * developer: which line, what was written, and the closest steps that do exist.
 */
public record ValidationError(
        long line,
        String step,
        String message,
        List<String> suggestions
) {
    public static ValidationError syntax(long line, String message) {
        return new ValidationError(line, null, message, List.of());
    }

    // Analyst-facing text is Turkish, like the rest of the UI. Gherkin's own
    // syntax errors stay in the parser's English — they come from the library.
    public static ValidationError undefinedStep(long line, String step, List<String> suggestions) {
        return new ValidationError(line, step, "Tanımsız adım", suggestions);
    }
}
