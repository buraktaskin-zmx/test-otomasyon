package orchestrator.llm;

import orchestrator.catalog.CatalogModel;
import orchestrator.vocabulary.Vocabulary;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops a draft from trying to {@code click} the grid row it enters the flow
 * through.
 *
 * The third rewriter here, after {@link LoginStep} and {@link DateVerb}, and it
 * exists for the reason both of those do: the problem was first addressed by
 * REPORTING it and reporting was not enough.
 *
 * What the model produced, on two adjacent lines:
 *
 * <pre>
 *   And click "FORD DBS ODEMELERI"        &lt;- spurious
 *   And select row "FORD DBS ODEMELERI"   &lt;- correct
 * </pre>
 *
 * Every check passed, and that is the interesting part. Both are real patterns,
 * the value is a real catalogued one, the label and type are right. The bug is
 * the RELATIONSHIP between two individually correct steps, and the only thing
 * that knows which of them is wrong is the catalog's {@code navigation.entry_row}
 * — {@code click} resolves {@code getByRole(BUTTON|LINK)}, and a grid cell is
 * neither, so the run spends its full thirty seconds and dies on a line that
 * merely repeated the next one.
 *
 * {@code ScenarioValidator} now catches it, and that check stays: it is the
 * backstop for when this rewriter declines to act. But a validator finding is
 * handed to the model as a REPAIR PROMPT, and a repair prompt is a request. The
 * date verbs had already settled what that is worth — the model wrote the wrong
 * verb, was shown the validator's own complaint, and wrote it again. So the
 * correction belongs where {@code DateVerb}'s does: applied, not asked for.
 *
 * Two cases, both decided from the catalog rather than from taste:
 *
 * <ul>
 *   <li>The draft ALSO selects the row properly — delete the {@code click}
 *       line. Nothing is lost; the line is a duplicate of work done correctly
 *       elsewhere in the same scenario.</li>
 *   <li>The draft only clicks it — rewrite the line to {@code select row}. The
 *       two steps take one argument each, in the same position, so this is the
 *       same textual swap {@code DateVerb} makes, with the same nothing to get
 *       wrong. The flow still needs that row chosen at that point, and now it
 *       is chosen with the step that can reach it.</li>
 * </ul>
 *
 * Deliberately narrow in three ways:
 *
 * <ul>
 *   <li>Only {@code entry_row}. {@code entry_tab} and {@code entry_button} are
 *       genuinely driven with {@code click} — the tabs are links — so a draft
 *       that clicks those is correct and is left alone.</li>
 *   <li>Only a value the catalog NAMES as the entry row. A click of anything
 *       else is not this rewriter's business, and guessing which other values
 *       might be grid cells would make this a second author of the scenario.</li>
 *   <li>Only when the deployment serves {@code select row}. See
 *       {@link #apply}.</li>
 * </ul>
 */
public final class EntryRow {

    /** The step a grid row is chosen with, when the deployment has it. */
    private static final String SELECT_ROW = "select row {string}";

    /**
     * {@code And click "<value>"} — the whole line, its terminator included, so
     * deleting a match leaves no blank line behind.
     *
     * Group 1 is the keyword and its indent, 2 the clicked value, 3 the line
     * ending (empty at end of file).
     */
    private static final Pattern CLICK = Pattern.compile(
            "(?m)^([ \\t]*(?:Given|When|Then|And|But)[ \\t]+)click[ \\t]+\"([^\"]*)\"[ \\t]*(\\R|$)");

    /**
     * {@code And select row "<value>"}, to learn whether the row is already
     * being chosen properly.
     *
     * The quoted argument is what keeps {@code select row at index {int}} out:
     * that sibling step takes a bare number, so it cannot match here.
     */
    private static final Pattern SELECT_ROW_STEP = Pattern.compile(
            "(?m)^[ \\t]*(?:Given|When|Then|And|But)[ \\t]+select row[ \\t]+\"([^\"]*)\"[ \\t]*$");

    private EntryRow() {
    }

    /**
     * @param catalog    may be null — an unparseable catalog switches this off,
     *                   exactly as it switches off every other check in this
     *                   pipeline. Without it there is no {@code entry_row} to
     *                   compare against and no way to tell a row from a button.
     * @param vocabulary the steps the DEPLOYED executors actually publish.
     *
     *                   The guard is not theoretical, and the lesson was paid
     *                   for by {@code DateVerb}: the orchestrator and the
     *                   executors restart separately, and on the run that found
     *                   it the orchestrator knew a step the executor did not.
     *                   Every rewritten line came back "Tanımsız adım", and
     *                   because an unknown step suppresses the catalog checks
     *                   entirely, the draft also lost every OTHER finding it
     *                   would have reported. A correction that outruns the
     *                   deployment is strictly worse than no correction. So when
     *                   {@code select row} is not published, the draft is left
     *                   as the model wrote it and the validator has its say.
     * @return the draft with the entry row's {@code click} removed or corrected
     */
    public static String apply(String feature, CatalogModel catalog, Vocabulary vocabulary) {
        if (feature == null || catalog == null || vocabulary == null) {
            return feature;
        }
        String row = catalog.entryRow();
        if (row.isEmpty() || !vocabulary.has(SELECT_ROW)) {
            return feature;
        }
        return rewrite(feature, row, alreadySelected(feature, row));
    }

    /**
     * Whether some step in the draft already chooses this row with the step
     * that can actually reach it.
     *
     * Decides between deleting the click and converting it, which is the whole
     * difference between losing a step the flow needs and keeping a duplicate.
     */
    private static boolean alreadySelected(String feature, String row) {
        Matcher selected = SELECT_ROW_STEP.matcher(feature);
        while (selected.find()) {
            if (fold(selected.group(1)).equals(fold(row))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks every {@code click} in the draft and acts on the ones naming the
     * entry row.
     *
     * The FIRST such click becomes the {@code select row} when the draft had
     * none; any further one is dropped. A draft with two clicks of the same row
     * and no selection is not something that has been seen, but the alternative
     * — emitting the step twice — would pick the row, then re-open the picker
     * on a screen that has already moved on, and that is a worse failure than
     * the one being fixed.
     *
     * {@code appendReplacement} is given a QUOTED replacement: these values come
     * off a real screen and a backslash or a dollar sign in one would otherwise
     * be read as a group reference.
     */
    private static String rewrite(String feature, String row, boolean alreadySelected) {
        Matcher click = CLICK.matcher(feature);
        StringBuilder out = new StringBuilder();
        boolean converted = alreadySelected;

        while (click.find()) {
            String replacement;
            if (!fold(click.group(2)).equals(fold(row))) {
                replacement = click.group(0);          // a button or a tab: not ours
            } else if (converted) {
                replacement = "";                     // duplicate: drop the line
            } else {
                replacement = click.group(1) + "select row \"" + click.group(2) + "\""
                        + click.group(3);
                converted = true;
            }
            click.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        click.appendTail(out);
        return out.toString();
    }

    /**
     * The same folding {@code ScenarioValidator} compares labels with, so the
     * two cannot disagree about whether a value matches the catalog. Locale.ROOT
     * rather than Turkish deliberately: it is the validator's choice, and one
     * definition of equality beats two correct ones.
     */
    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
