package orchestrator.product;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One scenario as the database keeps it: the natural-language half and the
 * Gherkin half in separate fields.
 *
 * They used to share one .feature file -- the prose as the Scenario's
 * description -- and editing one half could quietly damage the other: a
 * whole file pasted into the Gherkin box doubled the rule tags, and a
 * description that lost its section headers came back as "yok". Kept apart,
 * a Gherkin edit touches {@link #gherkin} and nothing else.
 *
 * {@link FeatureComposer} rebuilds the file a run or an export needs;
 * {@link ScenarioDocParser} goes the other way, for the import and for
 * clients that still send a whole file.
 *
 * @param title         the Scenario's name -- also the {@code Scenario:} line
 *                      inside {@link #gherkin}, kept in step by the store
 * @param rules         {@code R-AL-014}, without the '@'
 * @param needs         {@code FIRMA_TEST}, or {@code none} said out loud
 * @param otherTags     every other tag ({@code mutating}), without the '@'
 * @param gherkin       {@code Feature:} + {@code Scenario:} + steps; no tag line
 *                      above Feature and no description under Scenario
 * @param summary       "Açıklama": what the scenario is about, one item per
 *                      line. Context for a reader, not a step to perform
 */
public record ScenarioFields(String title, List<String> rules, List<String> needs,
                             List<String> otherTags, List<String> preconditions,
                             List<String> steps, List<String> expected, String gherkin,
                             List<String> summary) {

    /** Without an "Açıklama" -- for callers that never had one to give. */
    public ScenarioFields(String title, List<String> rules, List<String> needs,
                          List<String> otherTags, List<String> preconditions,
                          List<String> steps, List<String> expected, String gherkin) {
        this(title, rules, needs, otherTags, preconditions, steps, expected, gherkin, List.of());
    }

    public ScenarioFields {
        title = title == null ? "" : title.trim();
        rules = clean(rules);
        needs = clean(needs);
        otherTags = clean(otherTags);
        preconditions = prose(preconditions);
        steps = prose(steps);
        expected = prose(expected);
        summary = prose(summary);
        // No comment lines in any Gherkin, by the analysts' rule.
        gherkin = gherkin == null ? "" : COMMENT_LINE.matcher(gherkin).replaceAll("");
    }

    private static final Pattern COMMENT_LINE =
            Pattern.compile("(?m)^[ \\t]*#.*(?:\\R|$)");

    /** Trimmed, blank items dropped, a leading "- " a person typed removed. */
    private static List<String> clean(List<String> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item != null)
                .map(item -> item.trim().replaceFirst("^[-•*]\\s+", "").trim())
                .filter(item -> !item.isEmpty())
                .toList();
    }

    /** "1. ", "2) " typed or pasted in front of an item. */
    private static final Pattern NUMBERING = Pattern.compile("^\\d{1,3}[.)]\\s+");

    /** A section's own heading pasted in as an item: "Adımlar", "Ön koşul:", "Beklenen". */
    private static final Pattern HEADING = Pattern.compile(
            "(?iu)^(ad[ıi]mlar|(ö|o)n\\s*ko[şs]ul(lar)?|beklenen(ler)?|a[çc][ıi]klama)\\s*:?$");

    /**
     * {@link #clean}, plus what a list pasted from elsewhere brings with it.
     *
     * <p><b>Measured 12.11.2026</b> (tape 2628c4fd): "İptal ekranından Geri
     * ile listeye dönülür" was stored with the steps
     * {@code ["Adımlar", "1. Firma Seçimi…", "2. Listede…", …]} — the block
     * pasted with its own heading and numbers. Every consumer numbers the
     * steps itself, so the agent was handed {@code 1. Adımlar},
     * {@code 2. 1. Firma Seçimi…}, {@code 4. 3. … "Fatura İptal Et"…}: two
     * numberings that disagree, and a step 1 that is the word "Adımlar".
     *
     * <p>Applied here because every write AND every read builds this record
     * (ScenarioStore reads through it), so rows already saved that way are
     * cleaned the next time they are read, without a migration. Only the four
     * prose lists — never rules, needs or tags.
     */
    private static List<String> prose(List<String> items) {
        return clean(items).stream()
                .map(item -> NUMBERING.matcher(item).replaceFirst("").trim())
                .filter(item -> !item.isEmpty() && !HEADING.matcher(item).matches())
                .toList();
    }
}
