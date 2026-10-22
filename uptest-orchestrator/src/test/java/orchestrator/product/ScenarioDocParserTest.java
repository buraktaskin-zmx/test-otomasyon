package orchestrator.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A .feature file split into the database's fields, and composed back.
 *
 * The import reads every scenario through {@link ScenarioDocParser} once, and
 * every run reads it back through {@link FeatureComposer}; if the two disagree
 * a scenario changes just by being stored. These tests pin the round trip and
 * the one edit that used to corrupt a file: a whole file pasted where only
 * Gherkin belongs.
 */
class ScenarioDocParserTest {

    private static final String STRUCTURED = """
            @R-YL-006 @needs:FIRMA_TEST
            Feature: Alıcı Listesi İşlemleri

              Scenario: Yeni liste bilgileri onay özetinde görünür

                Ön koşul:
                - İlgili firma verisi bulunabilmeli

                Adımlar:
                - Alıcı Listesi İşlemleri ekranını aç
                - Devam'a bas

                Beklenen:
                - İşlem Bilgileri ekranı görünür

                Given login with cif "LH01600254" and userid "1081"
                And click "Devam"
                Then verify "İşlem Bilgileri" text is displayed
            """;

    @Test
    @DisplayName("tags, sections and steps land in their own fields")
    void splitsIntoFields() {
        ScenarioFields fields = ScenarioDocParser.parse(STRUCTURED);

        assertThat(fields.title()).isEqualTo("Yeni liste bilgileri onay özetinde görünür");
        assertThat(fields.rules()).containsExactly("R-YL-006");
        assertThat(fields.needs()).containsExactly("FIRMA_TEST");
        assertThat(fields.preconditions()).containsExactly("İlgili firma verisi bulunabilmeli");
        assertThat(fields.steps()).containsExactly("Alıcı Listesi İşlemleri ekranını aç", "Devam'a bas");
        assertThat(fields.expected()).containsExactly("İşlem Bilgileri ekranı görünür");
        assertThat(fields.gherkin())
                .startsWith("Feature: Alıcı Listesi İşlemleri")
                .contains("Given login with cif")
                .doesNotContain("@R-", "Adımlar:", "Ön koşul:");
    }

    @Test
    @DisplayName("a structured file survives the round trip unchanged")
    void roundTripIsExact() {
        assertThat(FeatureComposer.compose(ScenarioDocParser.parse(STRUCTURED))).isEqualTo(STRUCTURED);
    }

    @Test
    @DisplayName("free prose is read the way the detail view always read it")
    void legacyProse() {
        ScenarioFields fields = ScenarioDocParser.parse("""
                @R-AL-014 @needs:none
                Feature: Alici Listesi Islemleri

                  Scenario: Firma alani yazarak filtrelenir

                    Firma alanina tikla. Test firmasinin adinin bir bolumunu yaz.

                    Beklenen: Acilan listede yalnizca eslesen firmalar kalir.
                """);

        assertThat(fields.needs()).containsExactly("none");
        assertThat(fields.steps()).containsExactly("Firma alanina tikla",
                "Test firmasinin adinin bir bolumunu yaz");
        assertThat(fields.expected()).containsExactly("Acilan listede yalnizca eslesen firmalar kalir.");
    }

    @Test
    @DisplayName("a whole file pasted into the Gherkin box cannot double the rules")
    void pastedFileKeepsOnlyItsGherkin() {
        ScenarioFields current = ScenarioDocParser.parse(STRUCTURED);
        ProductService.ScenarioEdit paste = new ProductService.ScenarioEdit(
                null, STRUCTURED, null, null, null, null, null, null, null);

        ScenarioFields next = ProductService.merged(current, paste);
        String composed = FeatureComposer.compose(next);

        assertThat(next.rules()).containsExactly("R-YL-006");
        assertThat(next.steps()).isEqualTo(current.steps());
        assertThat(composed.lines().filter(line -> line.startsWith("@")).count()).isEqualTo(1);
        assertThat(composed).isEqualTo(STRUCTURED);
    }

    @Test
    @DisplayName("a new title renames the Scenario line too")
    void titleFollowsIntoGherkin() {
        ScenarioFields current = ScenarioDocParser.parse(STRUCTURED);
        ProductService.ScenarioEdit rename = new ProductService.ScenarioEdit(
                null, null, "Onay özeti doğru", null, null, List.of("Aç"), null, null, null);

        ScenarioFields next = ProductService.merged(current, rename);

        assertThat(next.gherkin()).contains("Scenario: Onay özeti doğru");
        assertThat(next.steps()).containsExactly("Aç");
        assertThat(next.preconditions()).isEqualTo(current.preconditions());
    }

    @Test
    @DisplayName("comment lines never reach a stored Gherkin")
    void commentsAreDropped() {
        ScenarioFields fields = ScenarioDocParser.parse("""
                Feature: X

                  Scenario: Y
                    # bir yorum
                    Given login with cif "A" and userid "B"
                """);

        assertThat(fields.steps()).isEmpty();
        assertThat(fields.gherkin()).doesNotContain("#");
        assertThat(FeatureComposer.compose(fields)).doesNotContain("#");
    }
}
