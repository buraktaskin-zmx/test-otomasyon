package orchestrator.validation;

import java.util.List;

/**
 * The answer to "would this scenario actually run?", produced without launching
 * a browser. Validation is not optional: the LLM invents steps and analysts edit
 * freely, and without this an unknown step is discovered only after minutes of
 * browser time have burned.
 */
public record ValidationResult(
        boolean valid,
        int stepCount,
        List<ValidationError> errors
) {
    public static ValidationResult ok(int stepCount) {
        return new ValidationResult(true, stepCount, List.of());
    }

    public static ValidationResult failed(int stepCount, List<ValidationError> errors) {
        return new ValidationResult(false, stepCount, errors);
    }
}
