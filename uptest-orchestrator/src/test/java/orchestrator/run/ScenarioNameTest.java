package orchestrator.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the UI contract BEFORE the TEST_RUN refactor touches it.
 *
 * app.js renders this string straight into the history table's "Senaryo"
 * column, and once persistence lands it is derived ONCE at insert and stored —
 * so a change here silently relabels every future row and nothing fails.
 */
class ScenarioNameTest {

    @Test
    @DisplayName("the scenario title wins, even when a Feature line came first")
    void scenarioBeatsFeature() {
        String feature = """
                Feature: Banko Ödeme Talebi

                  Scenario: Kullanıcı ödeme talep eder
                    Given login with cif "x" and userid "y"
                """;

        assertThat(ScenarioName.of(feature)).isEqualTo("Kullanıcı ödeme talep eder");
    }

    @Test
    @DisplayName("Senaryo: is read the same as Scenario:")
    void turkishScenarioKeyword() {
        assertThat(ScenarioName.of("Özellik: X\n  Senaryo: Fatura oluşturulur\n"))
                .isEqualTo("Fatura oluşturulur");
    }

    /**
     * ScenarioValidator.OUTLINE already knows both spellings of this keyword;
     * before the extraction, a Scenario Outline fell through to the FEATURE
     * title, so the history named it after the screen rather than the test.
     */
    @Test
    @DisplayName("Scenario Outline: is a scenario title, not a fallthrough")
    void scenarioOutline() {
        assertThat(ScenarioName.of("Feature: X\n  Scenario Outline: Tutar varyasyonları\n"))
                .isEqualTo("Tutar varyasyonları");
    }

    @Test
    @DisplayName("Senaryo Taslağı: likewise")
    void turkishScenarioOutline() {
        assertThat(ScenarioName.of("Özellik: X\n  Senaryo Taslağı: Tutar varyasyonları\n"))
                .isEqualTo("Tutar varyasyonları");
    }

    @Test
    @DisplayName("falls back to the feature title when there is no scenario")
    void fallsBackToFeatureTitle() {
        assertThat(ScenarioName.of("Feature: DBS Firm Invoice Inquiry\n"))
                .isEqualTo("DBS Firm Invoice Inquiry");
        assertThat(ScenarioName.of("Özellik: Banko Alacak İskonto\n"))
                .isEqualTo("Banko Alacak İskonto");
    }

    @Test
    @DisplayName("the placeholder, when nothing can be parsed")
    void placeholder() {
        assertThat(ScenarioName.of("")).isEqualTo(ScenarioName.UNNAMED);
        assertThat(ScenarioName.of("# just a comment\n")).isEqualTo(ScenarioName.UNNAMED);
        assertThat(ScenarioName.of(null)).isEqualTo(ScenarioName.UNNAMED);
    }

    /**
     * scenario_name is NOT NULL and a blank cell in the history table reads as
     * a rendering bug, so an empty title becomes the placeholder.
     */
    @Test
    @DisplayName("a title with nothing after the colon becomes the placeholder")
    void emptyTitleBecomesPlaceholder() {
        assertThat(ScenarioName.of("Feature: X\n  Scenario:\n")).isEqualTo(ScenarioName.UNNAMED);
        assertThat(ScenarioName.of("Feature:\n")).isEqualTo(ScenarioName.UNNAMED);
    }

    @Test
    @DisplayName("leading whitespace and a space before the colon are tolerated")
    void whitespaceTolerance() {
        assertThat(ScenarioName.of("\t\t  Scenario:   Ödeme   \n")).isEqualTo("Ödeme");
        assertThat(ScenarioName.of("  Scenario : Ödeme\n")).isEqualTo("Ödeme");
    }

    @Test
    @DisplayName("a colon inside the title is kept")
    void colonInsideTitleSurvives() {
        assertThat(ScenarioName.of("  Scenario: Ödeme: tüm tutar\n")).isEqualTo("Ödeme: tüm tutar");
    }

    /**
     * Truncated in Java, never by the column: H2 raises "Value too long for
     * column" rather than truncating, which would turn a verbose scenario
     * title into a 500 on the one run that used it.
     */
    @Test
    @DisplayName("a title longer than the column is truncated in Java, to exactly the column width")
    void longTitleIsTruncated() {
        String title = "Ö".repeat(ScenarioName.MAX_LENGTH + 100);

        String name = ScenarioName.of("  Scenario: " + title + "\n");

        assertThat(name).hasSize(ScenarioName.MAX_LENGTH).endsWith("…");
    }

    @Test
    @DisplayName("a title exactly at the column width is not touched")
    void titleAtTheLimitIsNotTruncated() {
        String title = "a".repeat(ScenarioName.MAX_LENGTH);

        assertThat(ScenarioName.of("  Scenario: " + title + "\n")).isEqualTo(title);
    }

    @Test
    @DisplayName("CRLF line endings parse the same as LF")
    void crlfIsHandled() {
        assertThat(ScenarioName.of("Feature: X\r\n  Scenario: Ödeme\r\n")).isEqualTo("Ödeme");
    }
}
