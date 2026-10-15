package orchestrator.llm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a batch draft's {@code Scenario Outline} back into the plain scenario
 * the planner asked for.
 *
 * The fourth rewriter here, after {@link LoginStep}, {@link DateVerb} and
 * {@link EntryRow}, and this one started as a CONTRADICTION in the prompt
 * rather than a rule the model ignored.
 *
 * The system prompt's rule 5 says, correctly for a single scenario:
 *
 *   "use exactly one 'Scenario:' unless the intent clearly asks for several
 *    input rows, in which case use one 'Scenario Outline:' with an 'Examples:'
 *    table."
 *
 * In a BATCH that permission is wrong, and the two halves of the prompt
 * disagree: {@link VariationPlanner} has already chosen this scenario's data
 * and the user turn lists it literally ("Fatura Tipi" = "E-Fatura"), so an
 * Examples table re-opens a decision that was already made. The model was
 * arguably obeying rule 5 — which is why it happened intermittently and only
 * on the scenarios whose combination looked "interesting" enough to tabulate.
 *
 * What it produced, on a run whose card correctly read "Fatura Tipi =
 * E-Fatura":
 *
 * <pre>
 *   And select "&lt;Fatura Tipi&gt;" from "Fatura Tipi"
 *   ...
 *   Examples:
 *     | Fatura Tipi |
 *     | Normal      |          &lt;- not even the value that was chosen
 * </pre>
 *
 * So the placeholder was unresolved AND the one row contradicted the plan. The
 * validator caught it, but only as "this field does not offer
 * '&lt;Fatura Tipi&gt;'", which names the symptom and not the cause.
 *
 * Deterministic because the answer is already known: the planner's map says
 * what every varied label must hold. This class does not read the Examples
 * table at all — the table is the model's invention, the plan is the truth.
 *
 * ALL OR NOTHING. A placeholder with no planned value cannot be resolved, and
 * flattening around it would leave a bare {@code &lt;X&gt;} in a scenario with
 * no Examples table left to feed it — worse than what we started with. In that
 * case the draft is returned untouched and the validator reports it.
 */
public final class OutlineFlattener {

    /** Both spellings, matching ScenarioValidator.OUTLINE. */
    private static final Pattern OUTLINE =
            Pattern.compile("(?m)^(\\s*)(?:Scenario Outline|Senaryo Taslağı)(\\s*:)");

    /** {@code <Fatura Tipi>} — a Gherkin placeholder. */
    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>]+)>");

    /**
     * The Examples block: its heading and every table row under it, to the end
     * of the table.
     *
     * The rows are matched as whole lines beginning with a pipe, so a table
     * that ends at a blank line, another keyword, or the end of the file all
     * work without special cases.
     */
    private static final Pattern EXAMPLES = Pattern.compile(
            "(?m)^[ \\t]*(?:Examples|Örnekler)[ \\t]*:[ \\t]*\\R(?:[ \\t]*\\|.*\\R?)*");

    private OutlineFlattener() {
    }

    /**
     * @param variation the combination this draft was asked to exercise, or
     *                  null on the single-scenario path — where an Outline is
     *                  legitimate and this class must do nothing. That null is
     *                  the whole switch: the same prompt rule is right there
     *                  and wrong here.
     * @return the draft with placeholders resolved and the Examples table
     *         removed, or the draft untouched when it cannot be done safely
     */
    public static String apply(String feature, VariationPlanner.Variation variation) {
        if (feature == null || variation == null) {
            return feature;
        }
        if (!OUTLINE.matcher(feature).find()) {
            return feature;
        }

        Map<String, String> planned = variation.values();
        StringBuilder out = new StringBuilder();
        Matcher hole = PLACEHOLDER.matcher(feature);

        while (hole.find()) {
            String value = planned.get(hole.group(1).trim());
            if (value == null) {
                // Not a label the planner assigned. It may be a checkbox, an
                // excluded field, or something the model made up — either way
                // we have no right answer for it, so nothing is changed and
                // the validator gets to say so.
                return feature;
            }
            hole.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        hole.appendTail(out);

        String flattened = EXAMPLES.matcher(out.toString()).replaceAll("");
        return OUTLINE.matcher(flattened).replaceAll("$1Scenario$2").stripTrailing() + "\n";
    }
}
