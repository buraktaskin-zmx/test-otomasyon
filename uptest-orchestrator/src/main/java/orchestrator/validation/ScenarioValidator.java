package orchestrator.validation;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Step;
import orchestrator.vocabulary.Vocabulary;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks a Gherkin document against the executor's step vocabulary — parse
 * errors first, then every step line.
 *
 * Uses the real Gherkin parser and Cucumber's own expression matcher, so the
 * line numbers and the accept/reject decision are the ones Cucumber would
 * produce. Synchronous and browser-free: it should cost milliseconds.
 */
@Service
public class ScenarioValidator {

    private static final int MAX_SUGGESTIONS = 3;

    public ValidationResult validate(String featureText, Vocabulary vocabulary) {
        List<ValidationError> errors = new ArrayList<>();

        GherkinParser parser = GherkinParser.builder().includePickles(false).build();

        List<Step> steps = new ArrayList<>();
        try (ByteArrayInputStream source = new ByteArrayInputStream(featureText.getBytes(StandardCharsets.UTF_8));
             Stream<Envelope> envelopes = parser.parse("scenario.feature", source)) {

            envelopes.forEach(envelope -> {
                envelope.getParseError().ifPresent(error -> errors.add(toSyntaxError(error)));
                envelope.getGherkinDocument().ifPresent(document -> collectSteps(document, steps));
            });
        } catch (Exception e) {
            errors.add(ValidationError.syntax(0, "Senaryo ayrıştırılamadı: " + e.getMessage()));
            return ValidationResult.failed(0, errors);
        }

        // A syntax error means the AST is incomplete; reporting "undefined step"
        // on top of it would just be noise.
        if (!errors.isEmpty()) {
            return ValidationResult.failed(steps.size(), errors);
        }

        if (steps.isEmpty()) {
            errors.add(ValidationError.syntax(0, "Adım bulunamadı: çalıştırılacak bir senaryo yok"));
            return ValidationResult.failed(0, errors);
        }

        for (Step step : steps) {
            String text = step.getText().trim();
            if (vocabulary.match(text).isEmpty()) {
                errors.add(ValidationError.undefinedStep(
                        step.getLocation().getLine(),
                        text,
                        vocabulary.suggestionsFor(text, MAX_SUGGESTIONS)));
            }
        }

        return errors.isEmpty()
                ? ValidationResult.ok(steps.size())
                : ValidationResult.failed(steps.size(), errors);
    }

    private static ValidationError toSyntaxError(ParseError error) {
        long line = error.getSource().getLocation().map(l -> l.getLine()).orElse(0L);
        return ValidationError.syntax(line, error.getMessage());
    }

    /** Background and scenario steps alike — both run, so both must be defined. */
    private static void collectSteps(GherkinDocument document, List<Step> steps) {
        document.getFeature().ifPresent(feature -> collectFromFeature(feature, steps));
    }

    private static void collectFromFeature(Feature feature, List<Step> steps) {
        for (FeatureChild child : feature.getChildren()) {
            child.getBackground().ifPresent(background -> steps.addAll(background.getSteps()));
            child.getScenario().ifPresent(scenario -> steps.addAll(scenario.getSteps()));
            child.getRule().ifPresent(rule -> {
                for (var ruleChild : rule.getChildren()) {
                    ruleChild.getBackground().ifPresent(background -> steps.addAll(background.getSteps()));
                    ruleChild.getScenario().ifPresent(scenario -> steps.addAll(scenario.getSteps()));
                }
            });
        }
    }
}
