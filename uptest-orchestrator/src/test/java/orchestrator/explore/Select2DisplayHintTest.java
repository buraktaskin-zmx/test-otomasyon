package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer an agent gets when it types into a Select2's display instead of
 * its search box. The failure text is the one the MCP server returned on
 * Firma Fatura İşlemleri, three times in one walk.
 */
class Select2DisplayHintTest {

    private static final String TYPED_INTO_THE_DISPLAY = """
            ### Error
            TimeoutError: browserBackend.callTool: Timeout 5000ms exceeded.
            Call log:
              - waiting for locator('aria-ref=e371')
                - locator resolved to <span role="textbox" title="Seçiniz" aria-readonly="true" \
            id="select2-firmIdx-container" class="select2-selection__rendered select2-chosen \
            nonprintable">Seçiniz</span>
                - fill("san")
              - attempting fill action
                2 × waiting for element to be visible, enabled and editable
                  - element is not editable
            """;

    @Test
    @DisplayName("typing into a dropdown's display is answered with where the search box is")
    void pointsAtTheSearchBox() {
        assertThat(BudgetedTool.select2DisplayHint(TYPED_INTO_THE_DISPLAY))
                .contains("searchbox")
                .contains("END of the page")
                .contains("WITHOUT a target");
    }

    @Test
    @DisplayName("any other failure gets no hint, so every other error stays as it was")
    void staysOutOfOtherFailures() {
        assertThat(BudgetedTool.select2DisplayHint(
                "Error: Ref f1e758 not found in the current page snapshot.")).isNull();
        assertThat(BudgetedTool.select2DisplayHint(
                "locator resolved to <a class=\"nonprintable\">…</a> - element is not visible")).isNull();
        assertThat(BudgetedTool.select2DisplayHint(null)).isNull();
    }
}
