package orchestrator.explore;

import orchestrator.catalog.CatalogModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A catalog draft taken out of the agent's answer, and what is mechanically
 * wrong with it before anyone reads it.
 *
 * Split out of {@link ExploreController} unchanged.
 */
final class CatalogDraftChecks {

    private CatalogDraftChecks() {
    }

    /**
     * The catalog on its own, with any preamble in front of it removed.
     *
     * Told firmly to output YAML and nothing else, the model still writes
     * paragraphs of reasoning first and the catalog after ("...Kataloğu
     * güncelliyorum:" then the YAML). LlmOutputCleaner strips fences and
     * think-blocks but has no way to know where prose ends and a document
     * begins.
     *
     * Every catalog starts with "app:" in column zero (SCHEMA.md makes it
     * required), so that line is the seam. When it is absent, the text is
     * returned untouched and {@link #problems} reports what is wrong with it
     * — better than silently handing back a fragment.
     */
    static String yamlOnly(String text) {
        if (text == null) {
            return "";
        }
        Matcher start = CATALOG_START.matcher(text);
        return start.find() ? text.substring(start.start()) : text;
    }

    private static final Pattern CATALOG_START = Pattern.compile("(?m)^app:\\s");

    /** The five types SCHEMA.md allows; anything else cannot be driven. */
    private static final Set<String> TYPES =
            Set.of("text", "date", "amount", "dropdown", "account_picker", "checkbox");

    /**
     * What is wrong with the draft, checked mechanically before anyone reads it.
     *
     * A model asked for YAML produces something that LOOKS like a catalog
     * whether or not it is one. These checks cost nothing and catch the
     * failures that otherwise surface minutes later in a browser: a type no
     * step can drive, a dropdown with no options to choose from, a navigation
     * block that cannot reach the screen.
     *
     * Reported, never corrected. Guessing what the model meant would put a
     * second author into the catalog and hide the very mistake worth seeing.
     */
    static List<String> problems(String yaml, Map<String, String> emptyChoiceReasons) {
        List<String> found = new ArrayList<>();

        CatalogModel model = CatalogModel.parse(yaml).orElse(null);
        if (model == null) {
            // CatalogModel.diagnose exists precisely so this line does not have
            // to guess: it re-reads the draft on the failure path only and
            // names the actual reason, down to SnakeYAML's line and column.
            // "ayristirilamadi ya da 'fields' bolumu yok" covered both a tab in
            // the indentation and an empty field list with one shrug, and the
            // analyst could act on neither.
            found.add("Katalog okunamadi: " + CatalogModel.diagnose(yaml));
            return found;
        }

        // Every field readonly means nobody types anything here: a detail or
        // summary screen, reached in order to be READ. It has no form to fill,
        // no final button and no transaction to complete — so the
        // success_message check further down, which exists to catch a
        // data-entry walk that stopped at the confirmation screen, would be
        // reporting the absence of something this screen never had.
        boolean readOnlyScreen = model.readOnlyScreen();

        for (String label : model.labels()) {
            CatalogModel.CatalogField field = model.field(label).orElseThrow();
            if (!TYPES.contains(field.type())) {
                found.add("\"" + label + "\" tipi '" + field.type()
                        + "' — SCHEMA.md yalnizca " + TYPES + " taniyor.");
            }
            boolean needsChoices = "dropdown".equals(field.type()) || "account_picker".equals(field.type());
            if (needsChoices && field.options().isEmpty()) {
                // Two different failures wearing one sentence until now. The
                // reason is a YAML COMMENT, which SnakeYAML drops, so it cannot
                // be read back out of the draft — the sink carries it instead.
                String reason = emptyChoiceReasons == null ? "" : emptyChoiceReasons.getOrDefault(label, "");
                found.add(reason.isBlank()
                        ? "\"" + label + "\" " + field.type()
                                + " ama hic secenek yazilmamis ve sebep de yazilmamis; ajan bu "
                                + "kontrolu muhtemelen hic ACMADI. Senaryo neyi sececegini bilemez."
                        : "\"" + label + "\" " + field.type() + " ama secenek yok. Ajan acmis ve bos "
                                + "bulmus: \"" + reason + "\". Ekranda gercekten secenek varsa "
                                + "katalogu elle doldurun.");
            }

            // "The box was not there" and "the box was there with an empty
            // list" look identical after a choice, and the agent conflated them
            // the first run it could express either: it put shown_when on
            // "Döviz Cinsi", whose options merely load once "Bayi Kodu" is set.
            //
            // A choice field that carries BOTH a condition and a filled option
            // list is that mistake's shape — the agent saw the list populate,
            // not the field appear. A genuinely revealed field is usually a
            // plain box ("E-Fatura Hash Kodu" is text), so this does not fire
            // on the real case.
            //
            // A question, not a verdict: a revealed dropdown is possible, and
            // only the screen can settle it. But the cost of being wrong is
            // one-sided and large, so it is worth asking every time.
            if (field.conditional() && needsChoices && !field.options().isEmpty()) {
                found.add("\"" + label + "\" hem shown_when tasiyor hem secenekleri dolu. Bu "
                        + "ekranda o sekil genellikle \"alan hep vardi, listesi \""
                        + field.shownWhenField() + "\" secilince yuklendi\" demektir — oysa "
                        + "shown_when \"" + field.shownWhenField() + " = "
                        + field.shownWhenValue() + " degilse alan ekranda YOK\" demek. "
                        + "Yanlissa pahali: uretilen senaryolardan yalnizca o degeri secen "
                        + "alani doldurur, digerleri ona DOKUNMAZ ve zorunlu bir alan bos "
                        + "kalir. Alan gercekten kayboluyorsa birakin; yalnizca listesi gec "
                        + "geliyorsa shown_when satirlarini silin.");
            }
        }

        // Cheap text checks: CatalogModel deliberately reads only fields, and
        // teaching it the navigation block just for this would be more schema
        // to keep in step.
        if (!yaml.contains("main_menu")) {
            found.add("navigation.main_menu yok — ekrana nasil gidilecegi yazili degil.");
        }
        if (!yaml.contains("sub_menu")) {
            found.add("navigation.sub_menu yok.");
        }
        if (yaml.contains("entry_button") && !yaml.contains("entry_row")) {
            found.add("entry_button var ama entry_row yok. Ekran bir izgaradan satir "
                    + "secilerek aciliyorsa bu satir eksik ve senaryo yanlis kaydi acar.");
        }

        // The two cannot honestly disagree: a success_message can only be read
        // on the result screen, which is reached THROUGH the confirmation
        // screen. So the agent saw that screen and did not record it, and the
        // omission is invisible in the YAML itself — nothing looks missing.
        if (yaml.contains("success_message") && !yaml.contains("verifiable")) {
            found.add("success_message var ama hicbir alan verifiable degil. Sonuc ekranina "
                    + "ulasilmis, demek ki onay ekranindan gecilmis, ama note_confirmation "
                    + "cagrilmamis. Bu katalogtan uretilen senaryo formu doldurur, Onay'a basar "
                    + "ve yalnizca banner'i dogrular: sessizce bos kalan bir alan yine yesil gecer.");
        }

        // The mirror of the check above, and the more common direction: it read
        // the confirmation screen and stopped there. Nothing in the YAML looks
        // wrong — the fields are all present and flagged — but the flow was
        // never finished, so the one text that proves the transaction completed
        // was never seen. A scenario built from this fills the form, presses
        // Devam, verifies the summary, and never presses the final button at
        // all.
        if (!readOnlyScreen && yaml.contains("verifiable") && !yaml.contains("success_message")) {
            found.add("Alanlar verifiable ama success_message yok. Ajan onay ekranini okudu ve "
                    + "orada durdu: son butona basmadi ya da sonuc ekrani gelmedi. Bu katalogtan "
                    + "uretilen senaryo islemin tamamlandigini KANITLAMAZ, yalnizca onay ekranini "
                    + "dogrular. Akisi sonuna kadar bir daha kosun ya da metni elle ekleyin.");
        }

        // A handful of verifiable fields out of many is the shape of a summary
        // table read only partway down — the agent's known weakness. It can
        // also be perfectly correct, because some screens really do echo a few
        // values, so this is a question put to the reviewer, not a verdict.
        long fields = yaml.lines().filter(line -> line.startsWith("  - label:")).count();
        long verifiable = yaml.lines().filter(line -> line.trim().equals("verifiable: true")).count();
        if (yaml.contains("success_message") && verifiable > 0 && verifiable * 2 < fields) {
            found.add(fields + " alandan yalnizca " + verifiable + " tanesi verifiable. Onay ekrani "
                    + "gercekten bu kadarini gosteriyorsa sorun yok; aksi halde ajan ozet tablosunu "
                    + "yarida birakmis ve senaryo geri kalan alanlarin dogru tasindigini hic "
                    + "kontrol etmeyecek. Ekrani bir gorup eksikleri elle ekleyin.");
        }
        return found;
    }
}
