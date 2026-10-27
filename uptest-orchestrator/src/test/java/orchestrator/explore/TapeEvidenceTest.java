package orchestrator.explore;

import orchestrator.validation.ValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three pieces that let a model write a walk's Gherkin safely: reading the
 * tape back, showing the model a short version of it, and refusing any word
 * the walk never contained.
 */
class TapeEvidenceTest {

    private static String ran(String code) {
        return "### Ran Playwright code\n```js\n" + code + "\n```\n### Page\n- Page URL: x";
    }

    private static String tree(String yaml) {
        return "### Page\n- Page URL: x\n### Snapshot\n```yaml\n" + yaml + "\n```";
    }

    private static List<TapeRenderer.Call> walk() {
        List<TapeRenderer.Call> calls = new ArrayList<>();
        calls.add(new TapeRenderer.Call(1, "browser_type", "{\"target\": \"f1e82\", \"text\": \"***\"}",
                "OK", ran("await page.getByRole('textbox', { name: 'Akıllı SMS Şifresi' }).fill('***');")));
        calls.add(new TapeRenderer.Call(2, "browser_click", "{\"target\": \"f1e148\"}", "OK",
                ran("await page.getByRole('link', { name: 'NAKİT YÖNETİMİ' }).click();")));
        calls.add(new TapeRenderer.Call(3, "browser_snapshot", "{}", "OK", tree("""
                - generic [ref=a1]:
                  - generic [ref=a2]: Fatura No
                  - textbox "Fatura No" [ref=a3]
                - generic [ref=a4]:
                  - generic [ref=a5]: Başlangıç Vadesi
                  - textbox "Başlangıç Vadesi" [ref=a6]""")));
        calls.add(new TapeRenderer.Call(4, "browser_type",
                "{\"target\": \"a3\", \"element\": \"Fatura No alanı\", \"text\": \"11113\"}", "OK",
                ran("await page.getByRole('textbox', { name: 'Fatura No' }).fill('11113');")));
        calls.add(new TapeRenderer.Call(5, "browser_click", "{\"target\": \"a9\"}", "ERROR",
                "Error calling tool: ### Error\\nError: Ref a9 not found in the current page snapshot."));
        calls.add(new TapeRenderer.Call(6, "set_date", "{\"target\": \"a6\", \"date\": \"27/10/2026\"}", "OK",
                "Could not set 27/10/2026: choose the year failed"));
        calls.add(new TapeRenderer.Call(7, "browser_snapshot", "{}", "OK", tree("""
                - generic [ref=a4]:
                  - generic [ref=a5]: Başlangıç Vadesi
                  - textbox "Başlangıç Vadesi" [ref=a6]
                  - generic [ref=a7]: Başlangıç vadesi seçiniz.""")));
        return calls;
    }

    @Test
    @DisplayName("the digest leaves the login out and marks what failed")
    void digestsTheWalk() {
        String digest = TapeEvidence.digest(walk(), "NAKİT YÖNETİMİ");

        assertThat(digest).doesNotContain("Akıllı SMS");
        assertThat(digest).contains("#4 browser_type | ajan: \"Fatura No alanı\" | text: \"11113\"");
        assertThat(digest).contains("#5 browser_click").contains("✗");
        assertThat(digest).contains("#6 set_date").contains("date: \"27/10/2026\"");
        assertThat(digest.lines().filter(line -> line.contains("#6 set_date")).findFirst().orElse(""))
                .contains("✗");
        assertThat(digest).contains("\"Fatura No\"").contains("\"Başlangıç Vadesi\"");
        assertThat(digest).contains("Başlangıç vadesi seçiniz.");
        assertThat(digest).doesNotContain("browser_snapshot");
    }

    @Test
    @DisplayName("a label the walk never showed is refused; what it did show passes")
    void refusesWhatTheWalkNeverHad() {
        String raw = "\"text\": \"11113\" … textbox \\\"Fatura No\\\" … Başlangıç Vadesi … "
                + "\"text\": \"1000\" … \"text\": \"50\" … Firma Seçimi … Sanko DBS Ödemeleri";
        String feature = """
                Feature: x
                  Scenario: y
                    Given login with cif "QZ1" and userid "3014"
                    And select "Sanko DBS Ödemeleri" from "Firma Seçimi"
                    And enter "Fatura No" "11113"
                    And enter amount "Toplam Tutar" "1000,50"
                    And select "Normal" from "Fatura Tipi"
                """;

        List<ValidationError> errors = Provenance.check(feature, raw, "Toplam Tutar: …", null);

        assertThat(errors).extracting(ValidationError::subject)
                .containsExactlyInAnyOrder("Normal", "Fatura Tipi");
        assertThat(errors).allSatisfy(error -> assertThat(error.line()).isEqualTo(7));
    }

    @Test
    @DisplayName("folding makes a value written without Turkish letters still match")
    void foldsBeforeComparing() {
        List<ValidationError> errors = Provenance.check(
                "Feature: x\n  Scenario: y\n    And select \"TL\" from \"Doviz Cinsi\"\n",
                "Döviz Cinsi … TL");
        assertThat(errors).isEmpty();
    }

    /**
     * Banko İşlemleri, 27 Oct: the model copied the whole BEKLENEN sentence
     * into one `text is displayed`. It passed the old check because the
     * sentence is in the scenario text; for this step only the screen counts.
     */
    @Test
    @DisplayName("text is displayed must be text a screen showed, not the analyst's sentence")
    void holdsTextIsDisplayedToTheScreen() {
        String screen = "- generic [ref=f1e406]: Hızlı Tarih Seçim\n- link \"Detaylı Arama\" [ref=f1e412]";
        String analyst = "Hızlı Tarih Seçim alanı görünür durumdadır - Sağ tarafta Detaylı Arama"
                + " bağlantısı görünür durumdadır";

        String wrong = "Feature: x\n  Scenario: y\n"
                + "    And verify \"" + analyst + "\" text is displayed\n";
        String right = """
                Feature: x
                  Scenario: y
                    Then verify "Hızlı Tarih Seçim" text is displayed
                    And verify "Detaylı Arama" text is displayed
                """;

        assertThat(Provenance.checkAgainstWalk(wrong, screen, analyst)).hasSize(1)
                .first().satisfies(error -> assertThat(error.message()).contains("EKRANDA"));
        assertThat(Provenance.checkAgainstWalk(right, screen, analyst)).isEmpty();
    }

    @Test
    @DisplayName("a BEKLENEN line joining two checks with a spaced dash becomes two")
    void splitsJoinedExpectations() {
        assertThat(TapeGherkinController.splitExpectations(List.of(
                "Hızlı Tarih Seçim alanı görünür durumdadır - Sağ tarafta Detaylı Arama bağlantısı görünür",
                "Toplam Tutar 1.000,00 TL",
                "Fatura Tipi e-Fatura olmalı")))
                .containsExactly(
                        "Hızlı Tarih Seçim alanı görünür durumdadır",
                        "Sağ tarafta Detaylı Arama bağlantısı görünür",
                        "Toplam Tutar 1.000,00 TL",
                        "Fatura Tipi e-Fatura olmalı");
    }

    @Test
    @DisplayName("a tape file reads back into calls, and a malformed id reads nothing")
    void readsATapeBack() throws Exception {
        String raw = """
                {"tapeId":"x","mainMenu":"NAKİT YÖNETİMİ","entries":[
                  {"seq":1,"tool":"browser_click","args":"{\\"target\\": \\"e1\\"}","outcome":"OK","result":"r"},
                  {"seq":2,"tool":"browser_snapshot","args":"{}","outcome":"ERROR","result":null}
                ]}""";
        TapeFile.Loaded loaded = TapeFile.parse("x", raw);

        assertThat(loaded.mainMenu()).isEqualTo("NAKİT YÖNETİMİ");
        assertThat(loaded.calls()).hasSize(2);
        assertThat(loaded.calls().get(0).args()).isEqualTo("{\"target\": \"e1\"}");
        assertThat(loaded.calls().get(1).result()).isNull();

        assertThat(TapeFile.isId("../../etc")).isFalse();
        assertThat(TapeFile.isId("fe25749f-20d4-49ca-bcee-0f31c5c243e7")).isTrue();
        assertThat(TapeFile.read(java.nio.file.Paths.get("."), "..\\x")).isEmpty();
    }
}
