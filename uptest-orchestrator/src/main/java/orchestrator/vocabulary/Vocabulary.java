package orchestrator.vocabulary;

import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The closed grammar a scenario may use, with the matcher that decides whether
 * a Gherkin line belongs to it.
 *
 * Matching uses Cucumber's own {@link CucumberExpression} rather than a
 * home-grown placeholder regex, so "the validator said yes" and "Cucumber found
 * the step" cannot disagree — which is the entire point of validating before a
 * browser is launched.
 */
public class Vocabulary {

    private final List<VocabularyStep> steps;
    private final Map<VocabularyStep, Expression> expressions;

    public Vocabulary(List<VocabularyStep> steps) {
        this.steps = List.copyOf(steps);
        this.expressions = compile(this.steps);
    }

    private static Map<VocabularyStep, Expression> compile(List<VocabularyStep> steps) {
        // ExpressionFactory is the public entry point, and it also accepts the
        // /regex/ form should a step definition ever use one.
        ExpressionFactory factory = new ExpressionFactory(new ParameterTypeRegistry(Locale.ENGLISH));
        Map<VocabularyStep, Expression> compiled = new LinkedHashMap<>();
        for (VocabularyStep step : steps) {
            try {
                compiled.put(step, factory.createExpression(step.pattern()));
            } catch (RuntimeException e) {
                // A pattern Cucumber itself cannot compile would fail at run
                // time too; skip it rather than refusing to validate anything.
                System.err.println("[vocabulary] uncompilable pattern '" + step.pattern() + "': " + e);
            }
        }
        return compiled;
    }

    public List<VocabularyStep> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** The step definition this Gherkin line would bind to, if any. */
    public Optional<VocabularyStep> match(String stepText) {
        return matchWithArguments(stepText).map(Match::step);
    }

    /** A bound step, together with the values Cucumber pulled out of the line. */
    public record Match(VocabularyStep step, List<String> arguments) {
    }

    /**
     * Like {@link #match}, but keeps the arguments.
     *
     * The plain match throws them away, which is all the vocabulary check
     * needs — but a catalog check lives entirely inside the quotes: whether
     * "Toplam Tutarı" is a real label, whether a dropdown was driven with
     * `enter`. Neither question can be asked without the values.
     */
    public Optional<Match> matchWithArguments(String stepText) {
        for (Map.Entry<VocabularyStep, Expression> entry : expressions.entrySet()) {
            List<Argument<?>> arguments = entry.getValue().match(stepText);
            if (arguments != null) {
                return Optional.of(new Match(entry.getKey(), values(arguments)));
            }
        }
        return Optional.empty();
    }

    private static List<String> values(List<Argument<?>> arguments) {
        List<String> values = new ArrayList<>(arguments.size());
        for (Argument<?> argument : arguments) {
            Object value = argument.getValue();
            values.add(value == null ? "" : value.toString());
        }
        return values;
    }

    /**
     * Closest patterns to an unrecognised line, so the analyst gets "did you
     * mean" instead of a bare rejection. Ranked by edit distance against the
     * pattern with its placeholders stripped.
     */
    public List<String> suggestionsFor(String stepText, int limit) {
        String needle = normalise(stepText);
        List<VocabularyStep> ranked = new ArrayList<>(steps);
        ranked.sort(Comparator.comparingInt(s -> distance(needle, normalise(s.skeleton()))));
        return ranked.stream().limit(limit).map(VocabularyStep::pattern).toList();
    }

    private static String normalise(String raw) {
        // Quoted arguments carry no signal about WHICH step was meant.
        return raw.toLowerCase(Locale.ROOT).replaceAll("\"[^\"]*\"", "").replaceAll("\\s+", " ").trim();
    }

    /** Plain Levenshtein distance; the lists here are tiny (tens of steps). */
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
}
