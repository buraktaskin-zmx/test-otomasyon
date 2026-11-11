package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "AI ile çalıştır" logs in and opens the screen in code, leaving one entry
 * each on the tape instead of the clicks the renderer used to look for.
 */
class TapeRendererPreludeTest extends TapeFixture {

    private void prelude() {
        ok(AiRunPrelude.LOGIN_TOOL, "{\"app\":\"ngc\",\"cif\":\"QZ06395288\",\"userId\":\"3014\"}", "Giriş yapıldı");
        ok(AiRunPrelude.MENU_TOOL, "{\"mainMenu\":\"NAKİT YÖNETİMİ\",\"screen\":\"Firma Fatura İşlemleri\"}",
                "açıldı");
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=e1]:
                  - link "Detay" [ref=e665]"""));
    }

    @Test
    @DisplayName("the menu entry IS the navigate step, and the agent's first click follows it")
    void preludeOpensTheScenario() {
        prelude();
        ok("browser_click", "{\"target\": \"e665\"}",
                ran("await page.getByRole('link', { name: 'Detay', exact: true }).click();"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "click \"Detay\"");
        assertThat(rendered.notes()).noneMatch(note -> note.contains("prelude"));
    }

    @Test
    @DisplayName("a menu the code could not open leaves the walk to find its own start, as before")
    void failedPreludeMenuIsIgnored() {
        ok(AiRunPrelude.LOGIN_TOOL, "{}", "Giriş yapıldı");
        failed(AiRunPrelude.MENU_TOOL, "{\"mainMenu\":\"NAKİT YÖNETİMİ\",\"screen\":\"X\"}", "timeout");
        reachTheScreen();

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"");
    }
}
