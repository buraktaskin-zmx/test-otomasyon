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

    /**
     * A label the catalog does not list. The step pattern itself is fine, so
     * the message has to say where the real mistake is — inside the quotes.
     */
    public static ValidationError unknownLabel(long line, String step, String label, List<String> nearest) {
        return new ValidationError(line, step,
                "Katalogda \"" + label + "\" diye bir alan yok", nearest);
    }

    /** The right pattern for what this field actually is. */
    public static ValidationError wrongVerb(long line, String step, String label, String type, String expected) {
        return new ValidationError(line, step,
                "\"" + label + "\" alanının tipi " + type + "; bu adım onu süremez",
                List.of(expected));
    }

    /** A value the field does not offer, answered with the values it does. */
    public static ValidationError unknownOption(long line, String step, String label, String value,
                                                List<String> allowed) {
        return new ValidationError(line, step,
                "\"" + label + "\" alanı \"" + value + "\" değerini sunmuyor", allowed);
    }
}
