package orchestrator.llm;

import orchestrator.catalog.CatalogModel;
import orchestrator.vocabulary.Vocabulary;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the one assertion a read-only screen allows, when the model did not.
 *
 * On a screen where every field is {@code readonly} the scenario fills nothing
 * in, so {@code checkAssertionsAreEarned} refuses every assertion that carries
 * an expected value — correctly: the model cannot know a value the application
 * derived, and inventing one is the failure that rule exists to stop. The only
 * legal assertion left is one that needs no value.
 *
 * Asked to "verify the recipient account is masked", the model reached for the
 * nearest value-shaped verb and wrote {@code has amount "*"}. Told that was
 * unearned, it deleted the assertion and returned a scenario that checks
 * nothing. Two attempts, two wrong answers, and the second was the one our own
 * error message had suggested.
 *
 * So this is written in Java rather than asked for again. The repo's own test
 * for which of the two a finding deserves: if the right answer is deterministic
 * and readable from the catalog, write the rewriter; if it needs a human's
 * judgement, report it. Here the catalog says the screen is read-only, the
 * request says which field and that it should be hidden, and the vocabulary has
 * exactly one step for that. Nothing is left to get wrong.
 *
 * Same shape and same guards as {@link DateVerb} and {@link EntryRow}: it runs
 * before the validator, so whatever it writes is still checked, and it refuses
 * to write a step the deployed executors do not publish.
 */
public final class ReadOnlyAssertion {

    /** The step this writes, when the deployment has it. */
    private static final String IS_MASKED = "verify the {string} field is masked";

    /** Any Gherkin step line; the LAST one is where a new assertion goes. */
    private static final Pattern STEP =
            Pattern.compile("(?m)^([ \\t]*)(?:Given|When|Then|And|But)[ \\t]+\\S.*$");

    /** Does the draft already assert anything at all? */
    private static final Pattern ANY_VERIFY =
            Pattern.compile("(?m)^[ \\t]*(?:Given|When|Then|And|But)[ \\t]+verify\\b");

    /**
     * How an analyst says "the application hides this value", folded.
     *
     * Stems rather than whole words, so "yıldızlı", "yildizli olarak" and
     * "maskelenmiş" all land. Deliberately short: this list only decides
     * whether to LOOK for a field, and a wrong hit still has to match a
     * catalogued label before anything is written.
     */
    private static final List<String> MASK_WORDS = List.of("yildiz", "maskel", "gizlen");

    private ReadOnlyAssertion() {
    }

    /**
     * @param catalog    may be null — then nothing is written, as everywhere
     *                   else in this pipeline
     * @param vocabulary the steps the DEPLOYED executors publish. Writing a step
     *                   nobody serves turns a reported problem into "Tanımsız
     *                   adım" and silences every other finding with it; that is
     *                   the lesson {@link DateVerb} paid for.
     * @param intent     the analyst's own words — the only place that says WHICH
     *                   field matters and what about it
     * @return the draft, with one assertion appended when all of the conditions
     *         hold and untouched otherwise
     */
    public static String apply(String feature, CatalogModel catalog, Vocabulary vocabulary,
                               String intent) {
        if (feature == null || catalog == null || vocabulary == null || intent == null) {
            return feature;
        }
        // Not a read-only screen: the model could have earned an assertion by
        // filling something in, and if it did not, that is a finding for the
        // validator to report rather than for us to paper over.
        if (!catalog.readOnlyScreen() || !vocabulary.has(IS_MASKED)) {
            return feature;
        }
        // The model asserted SOMETHING. Whether it is right is the validator's
        // business; adding a second assertion would make us a co-author of a
        // scenario that already has one.
        if (ANY_VERIFY.matcher(feature).find()) {
            return feature;
        }

        String asked = fold(intent);
        if (MASK_WORDS.stream().noneMatch(asked::contains)) {
            return feature;
        }
        String label = labelNamedIn(asked, catalog);
        if (label == null) {
            return feature;
        }
        return append(feature, label);
    }

    /**
     * The catalogued label the request names, longest match first.
     *
     * Longest wins for the same reason it does in {@code CatalogSink}: a short
     * label is a substring of half the sentences on these screens, and "Durum"
     * must not win over "Durum Kodu" when the analyst named the longer one.
     *
     * Containment rather than fuzzy distance, deliberately. This decides
     * whether to WRITE a line into someone's test; a near miss should produce
     * nothing and let the validator complain, not produce an assertion about
     * the wrong field.
     */
    private static String labelNamedIn(String foldedIntent, CatalogModel catalog) {
        String best = null;
        int longest = 0;
        for (String label : catalog.labels()) {
            String folded = fold(label);
            if (folded.length() > longest && foldedIntent.contains(folded)) {
                best = label;
                longest = folded.length();
            }
        }
        return best;
    }

    /** Puts the assertion after the last step, at that step's indentation. */
    private static String append(String feature, String label) {
        Matcher steps = STEP.matcher(feature);
        int end = -1;
        String indent = "    ";
        while (steps.find()) {
            end = steps.end();
            indent = steps.group(1);
        }
        if (end < 0) {
            return feature;
        }
        // Said out loud in the draft itself. DateVerb and EntryRow only correct
        // a line the model already wrote; this one ADDS a step the model never
        // wrote, and an analyst reading the scenario is entitled to know which
        // lines are theirs. The Gherkin parser ignores comments, so the
        // validator still sees exactly the steps that will run.
        String added = "\n" + indent
                + "# dogrulama orchestrator tarafindan eklendi: salt-okunur ekran ve"
                + " taslakta hic dogrulama yoktu\n"
                + indent + "Then verify the \"" + label + "\" field is masked";
        return feature.substring(0, end) + added + feature.substring(end);
    }

    /**
     * Turkish lowercase plus the diacritics folded away.
     *
     * Both halves are needed and for different reasons. The locale, because
     * only the Turkish rules fold "İ" and "I" the way these labels are written.
     * The diacritics, because analysts type the intent at speed and mix the two
     * alphabets within one sentence — the request that produced this class
     * reads "alıcı hesabının yıldızlı geldigini dogrula", Turkish in the first
     * half and ASCII in the second.
     *
     * Not shared with {@code ScenarioValidator.fold}, which folds under
     * Locale.ROOT and keeps diacritics: that one compares an expected VALUE
     * against the request and must stay strict, while this one matches a label
     * a human typed from memory.
     */
    private static final String TURKISH = "çğıöşüâîû";
    private static final String ASCII = "cgiosuaiu";
    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    private static String fold(String text) {
        String lowered = text.toLowerCase(TR);
        StringBuilder out = new StringBuilder(lowered.length());
        for (char c : lowered.toCharArray()) {
            int index = TURKISH.indexOf(c);
            out.append(index >= 0 ? ASCII.charAt(index) : c);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }
}
