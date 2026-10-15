package orchestrator.llm;

import orchestrator.catalog.CatalogModel;
import orchestrator.vocabulary.Vocabulary;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a draft's date steps to the verbs a date actually needs.
 *
 * The same move as {@link LoginStep#apply}, for the same reason. The prompt
 * tells the model that a {@code type: date} field is written with
 * {@code enter date} and read with {@code verify the {} has date {}}, and
 * explains why in four lines. The model wrote {@code enter "Başlangıç Vadesi"
 * "+1 ay"} anyway, then did it again through a repair turn that quoted the
 * validator's complaint back at it. A rule asked for is a rule sometimes
 * followed; a rule applied is a rule.
 *
 * Why it MATTERS rather than merely being untidy: the two steps are not
 * interchangeable the way two spellings would be.
 *
 * - {@code enter} would set the date — "+1 ay" goes into the box as those four
 *   characters, and the masked field paints its empty mask back. Silent.
 * - {@code verify the {} field equals {}} compares exactly, by design, because
 *   normalising once collapsed every expected value to "" and made two days of
 *   assertions pass against nothing. So it reads a screen showing 08/11/2026
 *   and looks for the literal "+1 ay". A field set perfectly fails its own
 *   assertion, minutes into a browser run.
 *
 * Narrow on purpose. Only dates, and only the verb: both pairs take their
 * arguments in the same order, so the rewrite is a textual swap with nothing to
 * get wrong. A dropdown written with {@code enter} needs its arguments
 * REVERSED to become {@code select ... from ...}, which is a different and
 * riskier edit — the validator reports those and the analyst fixes them, which
 * is the right split while they stay rare.
 *
 * Silent when the catalog cannot say. No catalog, an unknown label, a label of
 * some other type: the line is left exactly as the model wrote it and the
 * validator has its say. Guessing a verb from a VALUE that looks like a date
 * would make this a second author of the scenario.
 */
public final class DateVerb {

    /** The step a date is written with, when the deployment has it. */
    private static final String ENTER_DATE = "enter date {string} {string}";

    /** The step a date is read with, likewise. */
    private static final String VERIFY_DATE = "verify the {string} has date {string}";

    /** {@code enter "<label>" "<value>"} — the quotes are Gherkin's, not ours. */
    private static final Pattern ENTER = Pattern.compile(
            "(?m)^([ \\t]*(?:Given|When|Then|And|But)[ \\t]+)enter[ \\t]+\"([^\"]*)\"([ \\t]+\"[^\"]*\")[ \\t]*$");

    /** {@code verify the "<label>" field equals "<value>"} */
    private static final Pattern VERIFY = Pattern.compile(
            "(?m)^([ \\t]*(?:Given|When|Then|And|But)[ \\t]+)verify the[ \\t]+\"([^\"]*)\"[ \\t]+field equals"
                    + "([ \\t]+\"[^\"]*\")[ \\t]*$");

    private DateVerb() {
    }

    /**
     * @param catalog    may be null; then nothing is rewritten, exactly as when a
     *                   catalog will not parse anywhere else in this pipeline
     * @param vocabulary the steps the DEPLOYED executors actually publish. A
     *                   rewrite is only made when the target step is among them.
     *
     *                   That guard is not theoretical. The orchestrator and the
     *                   executors restart separately, and on the run that found
     *                   this, the orchestrator had `enter date` while the
     *                   executor did not: every rewritten line came back
     *                   "Tanımsız adım", and because an unknown step suppresses
     *                   the catalog checks, the draft lost its OTHER findings
     *                   too. Rewriting into a step nobody serves turns a
     *                   reported problem into an unrunnable scenario — strictly
     *                   worse than leaving the model's own verb and letting the
     *                   validator complain about it.
     * @return the draft with date fields' verbs corrected
     */
    public static String apply(String feature, CatalogModel catalog, Vocabulary vocabulary) {
        if (feature == null || catalog == null || vocabulary == null) {
            return feature;
        }
        String out = vocabulary.has(ENTER_DATE)
                ? rewrite(feature, ENTER, catalog, "enter date \"")
                : feature;
        return vocabulary.has(VERIFY_DATE)
                ? rewrite(out, VERIFY, catalog, "verify the \"")
                : out;
    }

    /**
     * One pattern's worth of rewriting.
     *
     * {@code appendReplacement} is given a QUOTED replacement because a label
     * can hold a backslash or a dollar sign — "Ödeme / Tahsilat" is tame, but
     * these labels come off a real screen and nothing says they stay tame.
     */
    private static String rewrite(String feature, Pattern pattern, CatalogModel catalog, String head) {
        Matcher step = pattern.matcher(feature);
        StringBuilder out = new StringBuilder();

        while (step.find()) {
            String label = step.group(2);
            String replacement = isDate(catalog, label)
                    ? step.group(1) + head + label + "\""
                            + (head.startsWith("verify") ? " has date" : "") + step.group(3)
                    : step.group(0);
            step.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        step.appendTail(out);
        return out.toString();
    }

    private static boolean isDate(CatalogModel catalog, String label) {
        return catalog.field(label).map(field -> "date".equals(field.type())).orElse(false);
    }
}
