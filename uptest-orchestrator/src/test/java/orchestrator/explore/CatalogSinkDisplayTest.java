package orchestrator.explore;

import orchestrator.catalog.CatalogModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read-only screen: a flow whose test is "go here and check what it says".
 *
 * Banko → Ödeme İşlemi → Yanıt Bekleyenler → a reference number → Detay has no
 * form on it. The agent walked it correctly and the draft still came back with
 * an empty {@code fields} block and "YAML ayrıştırılamadı", because every tool
 * it had asked for something a data-entry screen has: a type to drive, a list
 * to open, a value to fill in. {@code note_field} had nothing to record, so
 * nothing was recorded.
 *
 * These tests pin the shape that fixes it, and the two things most likely to be
 * undone by a later edit: that such a catalog PARSES (an empty field list does
 * not), and that the printed value is used for its type and then dropped.
 */
class CatalogSinkDisplayTest {

    /** The ten rows the agent actually reported from the Banko Detay screen. */
    private static CatalogSink walkedTheDetailScreen() {
        CatalogSink sink = new CatalogSink();
        sink.noteDisplay("Ödeme Tutarı", "15,00 TL");
        sink.noteDisplay("Garanti Tutarı", "15,00 TL");
        sink.noteDisplay("Komisyon Tutarı", "0,01 TL");
        sink.noteDisplay("Vade Tarihi", "11/09/2026");
        sink.noteDisplay("Alıcı Hesabı", "20****694");
        sink.noteDisplay("Referans Numarası", "10100500");
        sink.noteDisplay("Durum", "Onayınızı Bekleniyor");
        return sink;
    }

    private static String submit(CatalogSink sink) {
        sink.submitCatalog("NGC", "Banko Ödeme Detay", "NAKİT YÖNETİMİ", "Banko İşlemleri",
                "Yanıt Bekleyenler", "10100500", "Detay", "",
                null, List.of("Detay"), "", List.of());
        return sink.yaml();
    }

    @Test
    @DisplayName("a screen with nothing to fill in still produces a catalog that PARSES")
    void readOnlyScreenParses() {
        String yaml = submit(walkedTheDetailScreen());

        // The whole bug in one assertion. CatalogModel.parse returns empty for
        // an empty field list, and ExploreController.problems() reports that as
        // a broken draft — which is what the analyst saw on a walk that had
        // gone perfectly.
        assertThat(CatalogModel.parse(yaml)).isPresent();
        assertThat(CatalogModel.parse(yaml).orElseThrow().labels())
                .contains("Ödeme Tutarı", "Vade Tarihi", "Referans Numarası", "Durum");
    }

    @Test
    @DisplayName("every displayed field is readonly and verifiable")
    void displayedFieldsAreReadonlyAndVerifiable() {
        String yaml = submit(walkedTheDetailScreen());

        // readonly because a scenario must never type into it, verifiable
        // because being on screen to be read is the entire point of the field.
        assertThat(CatalogModel.parse(yaml).orElseThrow().fields())
                .isNotEmpty()
                .allSatisfy(field -> assertThat(field.readonly()).as(field.label()).isTrue());
        assertThat(yaml).contains("verifiable: true");
    }

    @Test
    @DisplayName("the printed value decides the type: amount, date, everything else text")
    void typeIsReadOffTheValue() {
        CatalogModel model = CatalogModel.parse(submit(walkedTheDetailScreen())).orElseThrow();

        // This is what picks the verify verb. "verify ... has amount"
        // normalises 1.250,50; "verify ... field equals" compares letter by
        // letter. Getting it wrong writes a scenario that fails on a screen
        // where nothing is wrong.
        assertThat(model.field("Ödeme Tutarı").orElseThrow().type()).isEqualTo("amount");
        assertThat(model.field("Komisyon Tutarı").orElseThrow().type()).isEqualTo("amount");
        assertThat(model.field("Vade Tarihi").orElseThrow().type()).isEqualTo("date");
        assertThat(model.field("Alıcı Hesabı").orElseThrow().type()).isEqualTo("text");
        assertThat(model.field("Referans Numarası").orElseThrow().type()).isEqualTo("text");
        assertThat(model.field("Durum").orElseThrow().type()).isEqualTo("text");
    }

    @Test
    @DisplayName("the value itself never reaches the catalog — a catalog carries no test data")
    void valueIsNotWrittenDown() {
        String yaml = submit(walkedTheDetailScreen());

        // "15,00 TL" is THIS RECORD's amount, not this screen's. A catalog that
        // carried it would be a template, and one catalog would stop serving
        // many scenarios.
        assertThat(yaml)
                .doesNotContain("15,00")
                .doesNotContain("11/09/2026")
                .doesNotContain("20****694")
                .doesNotContain("Onayınızı Bekleniyor");
        // The entry row is navigation, not data, and does belong there.
        assertThat(yaml).contains("entry_row: \"10100500\"");
    }

    @Test
    @DisplayName("a label reported with its value glued on is trimmed back to the label")
    void gluedLabelIsSplit() {
        CatalogSink sink = new CatalogSink();
        // What a detail screen actually prints is one run of text, and an agent
        // told to report what it sees reports exactly this. The value arrives
        // as its own argument, so the repair is exact rather than guesswork.
        sink.noteDisplay("Ödeme Tutarı 15,00 TL", "15,00 TL");

        assertThat(CatalogModel.parse(submit(sink)).orElseThrow().labels())
                .containsExactly("Ödeme Tutarı");
    }

    @Test
    @DisplayName("a field already noted while filling the form is flagged, not overwritten")
    void doesNotClobberAFieldThatWasFilledIn() {
        CatalogSink sink = new CatalogSink();
        sink.noteField("Bayi Kodu", "dropdown", List.of("93001", "93002"),
                null, null, null, "", "");
        // The same field, seen again on the confirmation screen. Its type was
        // read off the live control and its options cost a click to learn;
        // replacing them with a guess made from one printed value would throw
        // away the expensive half of the walk.
        sink.noteDisplay("Bayi Kodu", "93002");

        String yaml = submit(sink);
        CatalogModel.CatalogField field =
                CatalogModel.parse(yaml).orElseThrow().field("Bayi Kodu").orElseThrow();
        assertThat(field.type()).isEqualTo("dropdown");
        assertThat(field.options()).containsExactly("93001", "93002");
        assertThat(field.readonly()).isFalse();
        assertThat(yaml).contains("verifiable: true");
    }

    @Test
    @DisplayName("note_confirmation on a screen where nothing was filled in points at note_display")
    void confirmationOnAReadScreenRedirects() {
        String reply = new CatalogSink()
                .noteConfirmation(List.of("Ödeme Tutarı 15,00 TL", "Referans Numarası 10100500"));

        // Before this, every label came back as "the walk missed them" — which
        // sent the agent looking for form controls that do not exist on the
        // screen in front of it. The walk missed nothing.
        assertThat(reply).contains("note_display").doesNotContain("missed");
    }

    @Test
    @DisplayName("a read-only catalog is not asked for a success_message")
    void readOnlyScreenIsNotAskedToProveATransaction() throws Exception {
        List<String> problems = problems(submit(walkedTheDetailScreen()));

        // That check exists to catch a data-entry walk that stopped at the
        // confirmation screen without pressing the final button. A detail
        // screen has no final button and completes no transaction, so the
        // complaint would be reporting the absence of something it never had.
        assertThat(problems).noneMatch(p -> p.contains("success_message yok"));
    }

    @Test
    @DisplayName("an unreadable draft is reported with the reason, not a shrug")
    void parseFailureNamesTheCause() throws Exception {
        assertThat(problems("app: NGC\nflow: \"x\"\nfields:\n"))
                .anyMatch(p -> p.contains("fields"));
    }

    /** {@code ExploreController.problems} is private; the contract test reflects too. */
    @SuppressWarnings("unchecked")
    private static List<String> problems(String yaml) throws Exception {
        Method method = ExploreController.class
                .getDeclaredMethod("problems", String.class, Map.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(null, yaml, Map.of());
    }
}
