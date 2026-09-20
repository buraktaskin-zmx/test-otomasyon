package orchestrator.validation;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Step;
import orchestrator.catalog.CatalogModel;
import orchestrator.vocabulary.Vocabulary;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        return validate(featureText, vocabulary, null);
    }

    /**
     * The same checks, plus what the catalog can settle.
     *
     * A step can match its pattern perfectly and still be wrong: the mistake
     * lives inside the quotes. Both generations of the DBS scenario carried
     * exactly one such line — `enter` on a dropdown, then a label with a
     * stray letter — and both cost a browser run to find. The catalog knows
     * the answer to both, in milliseconds.
     *
     * @param catalog may be null; then nothing below the vocabulary check runs
     */
    public ValidationResult validate(String featureText, Vocabulary vocabulary, CatalogModel catalog) {
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

        // Only once every line is a known step. Asking the catalog about a
        // line that binds to nothing would report the same mistake twice.
        if (errors.isEmpty() && catalog != null) {
            for (Step step : steps) {
                checkAgainstCatalog(step, vocabulary, catalog, errors);
            }
        }

        return errors.isEmpty()
                ? ValidationResult.ok(steps.size())
                : ValidationResult.failed(steps.size(), errors);
    }

    /* ========================== catalog checks ========================== */

    /**
     * Which argument is the field label and which is the value.
     *
     * Derived from the pattern text rather than from a hand-kept table of
     * step names: a table goes stale the day someone adds a step, and it goes
     * stale SILENTLY. The convention it reads is the one already written down
     * in VOCABULARY.md and enforced in the prompt — a pattern with "from"
     * names the value first.
     *
     * Anything this cannot place is not a field step, or not one this class
     * understands; either way it is skipped. Skipping is the safe direction:
     * a false rejection would spend a repair turn "fixing" a correct line.
     */
    private record Roles(int label, int value) {
    }

    private static Roles roles(String pattern, int argumentCount) {
        if (argumentCount < 2) {
            return null;
        }
        if (pattern.contains(" from ")) {
            return new Roles(1, 0);
        }
        if (pattern.startsWith("enter ") || pattern.startsWith("verify the ")) {
            return new Roles(0, 1);
        }
        return null;
    }

    private void checkAgainstCatalog(Step step, Vocabulary vocabulary, CatalogModel catalog,
                                     List<ValidationError> errors) {
        String text = step.getText().trim();
        Vocabulary.Match match = vocabulary.matchWithArguments(text).orElse(null);
        if (match == null) {
            return;
        }
        String pattern = match.step().pattern();
        Roles roles = roles(pattern, match.arguments().size());
        if (roles == null) {
            return;
        }

        long line = step.getLocation().getLine();
        String label = match.arguments().get(roles.label());
        String value = match.arguments().get(roles.value());

        CatalogModel.CatalogField field = catalog.field(label).orElse(null);
        if (field == null) {
            errors.add(ValidationError.unknownLabel(line, text, label, nearestLabels(label, catalog)));
            return;
        }

        // A verify step reads; every type can be read, so only the writing
        // steps have a type to disagree with.
        if (!pattern.startsWith("verify ")) {
            String expected = expectedPattern(field);
            if (expected != null && !pattern.equals(expected)) {
                errors.add(ValidationError.wrongVerb(line, text, label, describeType(field), expected));
                return;
            }
            checkValue(line, text, field, value, errors);
        }
    }

    /**
     * The one step that drives this field, or null when the catalog does not
     * say enough to be sure.
     */
    private static String expectedPattern(CatalogModel.CatalogField field) {
        if (field.readonly()) {
            // Not a typing mistake but the same shape of one: the application
            // owns this value, so no writing step is correct here.
            return "verify the {string} field equals {string}";
        }
        return switch (field.type()) {
            case "text", "date" -> "enter {string} {string}";
            case "amount" -> "enter amount {string} {string}";
            case "dropdown" -> "select {string} from {string}";
            case "account_picker" -> "select account {string} from {string}";
            default -> null;
        };
    }

    private static String describeType(CatalogModel.CatalogField field) {
        return field.readonly() ? "readonly (uygulama dolduruyor)" : field.type();
    }

    /**
     * Whether the field offers this value at all.
     *
     * Silent when the catalog lists nothing: an empty "options" means the
     * catalog did not say, which really happens — discovery cannot read a
     * dependent dropdown's list before its parent is chosen. Treating that as
     * "nothing is allowed" would reject every correct scenario for such a
     * field.
     */
    private static void checkValue(long line, String text, CatalogModel.CatalogField field, String value,
                                   List<ValidationError> errors) {
        List<String> allowed = field.options();
        if (allowed.isEmpty()) {
            return;
        }
        if ("account_picker".equals(field.type())) {
            // Matched the way selectAccount matches: on digits, so the
            // catalog's spacing and the scenario's need not agree.
            String wanted = digits(value);
            boolean known = !wanted.isEmpty()
                    && allowed.stream().anyMatch(account -> digits(account).contains(wanted));
            if (!known) {
                errors.add(ValidationError.unknownOption(line, text, field.label(), value, allowed));
            }
            return;
        }
        if (!allowed.contains(value.trim())) {
            errors.add(ValidationError.unknownOption(line, text, field.label(), value, allowed));
        }
    }

    private static String digits(String text) {
        return text.replaceAll("\\D", "");
    }

    /** Closest catalogued labels, so "Toplam Tutarı" answers with "Toplam Tutar". */
    private static List<String> nearestLabels(String label, CatalogModel catalog) {
        return catalog.labels().stream()
                .sorted(Comparator.comparingInt(candidate -> distance(fold(label), fold(candidate))))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Plain Levenshtein; the label lists here are tens of entries at most. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
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
