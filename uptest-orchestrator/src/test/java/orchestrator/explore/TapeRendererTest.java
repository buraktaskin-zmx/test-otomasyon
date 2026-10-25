package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recorded walk of Firma Fatura İşlemleri, turned back into steps.
 *
 * The fixture is trimmed from a real tape rather than invented: every result
 * here is the shape the MCP server actually returns, including the two that
 * cost a round of debugging — a submenu whose accessible name carries a
 * "YENİ" badge, and an option whose echoed name is SHORTER than the one on
 * screen ("APPLE A.Ş" against "APPLE A.Ş.").
 */
class TapeRendererTest {

    private static final String MAIN_MENU = "NAKİT YÖNETİMİ";

    private final List<TapeRenderer.Call> calls = new ArrayList<>();
    private int seq;

    private void ok(String tool, String args, String result) {
        calls.add(new TapeRenderer.Call(++seq, tool, args, "OK", result));
    }

    private void failed(String tool, String args, String result) {
        calls.add(new TapeRenderer.Call(++seq, tool, args, "ERROR", result));
    }

    /** What a click comes back with: the line it ran, and nothing resolvable. */
    private static String ran(String code) {
        return "### Ran Playwright code\n```js\n" + code + "\n```\n### Page\n- Page URL: x";
    }

    private static String tree(String yaml) {
        return "### Page\n- Page URL: x\n### Snapshot\n```yaml\n" + yaml + "\n```";
    }

    private void login() {
        ok("browser_navigate", "{\"url\": \"https://x/indexNgc.do\"}", ran("await page.goto('https://x');"));
        ok("browser_fill_form", "{\"fields\": [{\"target\": \"f1e28\", \"value\": \"QZ06395288\"}]}",
                ran("await page.locator('#userCode').fill('QZ06395288');"));
        ok("browser_click", "{\"target\": \"f1e42\"}",
                ran("await page.getByRole('link', { name: 'Giriş', exact: true }).click();"));
        ok("browser_type", "{\"target\": \"f1e82\", \"text\": \"***\"}",
                ran("await page.getByRole('textbox', { name: 'Akıllı SMS Şifresi' }).fill('***');"));
        ok("browser_click", "{\"target\": \"f1e105\"}",
                ran("await page.getByRole('link', { name: 'Giriş' }).click();"));
    }

    private void reachTheScreen() {
        ok("browser_snapshot", "{}", tree("""
                - list [ref=f1e132]:
                  - listitem [ref=f1e147]:
                    - link "NAKİT YÖNETİMİ" [ref=f1e148]"""));
        ok("browser_click", "{\"target\": \"f1e148\"}",
                ran("await page.getByRole('link', { name: 'NAKİT YÖNETİMİ' }).click();"));
        // The badge is a child node, which is what makes it subtractable.
        ok("browser_snapshot", "{}", tree("""
                - list [ref=f1e287]:
                  - listitem [ref=f1e292]:
                    - link "Firma Fatura İşlemleri YENİ" [ref=f1e293]:
                      - generic [ref=f1e294]: YENİ"""));
        ok("browser_click", "{\"target\": \"f1e293\"}",
                ran("await page.getByRole('link', { name: 'Firma Fatura İşlemleri YENİ' }).click();"));
    }

    @Test
    @DisplayName("a walk becomes the steps an analyst reads, login left to LoginStep")
    void rendersTheWalk() {
        login();
        reachTheScreen();

        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e366]:
                  - generic [ref=f1e367]: Firma Seçimi
                  - combobox [ref=f1e370]:
                    - textbox "Seçiniz" [ref=f1e371]"""));
        ok("browser_click", "{\"target\": \"f1e371\"}",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - listbox [ref=f1e433]:
                  - option "APPLE A.Ş." [ref=f1e439]
                  - option "ASAŞ ALÜMİNYUM SAN." [ref=f1e441]"""));
        // Echoed without exact, and SHORTER than the screen's own text.
        ok("browser_click", "{\"target\": \"f1e439\"}",
                ran("await page.getByRole('option', { name: 'APPLE A.Ş' }).click();"));

        ok("browser_snapshot", "{}", tree("""
                - rowgroup [ref=f1e611]:
                  - row [ref=f1e612]:
                    - cell [ref=f1e613]:
                      - radio [ref=f1e614]
                    - cell "151732" [ref=f1e615]
                    - cell "cif1" [ref=f1e616]
                  - row [ref=f1e621]:
                    - cell [ref=f1e622]:
                      - radio [ref=f1e623]
                    - cell "151732" [ref=f1e624]
                    - cell "1111" [ref=f1e625]"""));
        ok("browser_click", "{\"target\": \"f1e614\"}",
                ran("await page.getByRole('row', { name: '151732 cif1' }).getByRole('radio').click();"));

        ok("browser_find", "{\"text\": \"detay\"}", "- link \"Detay\" [ref=f1e665]");
        ok("browser_click", "{\"target\": \"f1e665\"}",
                ran("await page.getByRole('link', { name: 'Detay', exact: true }).click();"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU);

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "select \"APPLE A.Ş.\" from \"Firma Seçimi\"",
                "select row \"cif1\"",
                "click \"Detay\"");
    }

    /**
     * The credential half is never replayed: the tape holds the password and
     * the SMS code, and LoginStep already grafts a login line from the test
     * user. Nothing before the main menu becomes a step.
     */
    @Test
    @DisplayName("the login is not rendered")
    void leavesTheLoginOut() {
        login();
        reachTheScreen();

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU);

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"");
        assertThat(String.join(" ", rendered.steps())).doesNotContain("Giriş", "QZ06395288");
    }

    /**
     * Five rows of this grid carry the same dealer code, so a step built from
     * the first cell would match all five. The identifying cell is whichever
     * one the siblings do not share, and which column that is cannot be known
     * in advance.
     */
    @Test
    @DisplayName("a row is named by the cell its siblings do not share")
    void picksAnIdentifyingCell() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - rowgroup [ref=g]:
                  - row [ref=r1]:
                    - cell [ref=c0]:
                      - radio [ref=rad1]
                    - cell "151732" [ref=c1]
                    - cell "Ödenecek" [ref=c2]
                    - cell "belge5" [ref=c3]
                  - row [ref=r2]:
                    - cell "151732" [ref=c4]
                    - cell "Ödenecek" [ref=c5]
                    - cell "belge4" [ref=c6]"""));
        ok("browser_click", "{\"target\": \"rad1\"}",
                ran("await page.getByRole('row').getByRole('radio').click();"));

        assertThat(TapeRenderer.render(calls, MAIN_MENU).steps())
                .contains("select row \"belge5\"");
    }

    /**
     * A failed call is a fact about the walk. Dropping it silently would make
     * a tape that lost half its steps look like a short scenario.
     */
    @Test
    @DisplayName("a call that failed is named, not dropped")
    void reportsFailedCalls() {
        login();
        reachTheScreen();
        failed("browser_click", "{\"target\": \"f1e393\"}", "Error: Ref f1e393 not found");

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU);

        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("browser_click").contains("error"));
    }

    /**
     * The agent does not assert; it reads a snapshot and describes it. Saying
     * so is what keeps a rendered scenario from looking complete when it is
     * missing its THEN.
     */
    @Test
    @DisplayName("the missing assertion is always said out loud")
    void alwaysSaysTheAssertionIsMissing() {
        login();
        reachTheScreen();

        assertThat(TapeRenderer.render(calls, MAIN_MENU).notes())
                .anySatisfy(note -> assertThat(note).contains("BEKLENEN"));
    }

    /**
     * A walk that never reached the screen renders nothing, and says that
     * rather than handing back an empty scenario.
     */
    @Test
    @DisplayName("a walk that never reached the menu renders nothing and says so")
    void reportsAWalkThatWentNowhere() {
        login();

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU);

        assertThat(rendered.steps()).isEmpty();
        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("hiçbir adım"));
    }
}
