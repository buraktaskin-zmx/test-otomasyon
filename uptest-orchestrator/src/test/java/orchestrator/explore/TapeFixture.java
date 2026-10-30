package orchestrator.explore;

import java.util.ArrayList;
import java.util.List;

/**
 * The recorded calls a renderer test builds up, and the walk every one of them
 * starts with: the login and the way into Firma Fatura İşlemleri.
 *
 * Split out of TapeRendererTest unchanged; each test gets a fresh instance, so
 * {@code calls} and {@code seq} are still per test.
 */
abstract class TapeFixture {

    static final String MAIN_MENU = "NAKİT YÖNETİMİ";

    final List<TapeRenderer.Call> calls = new ArrayList<>();
    int seq;

    void ok(String tool, String args, String result) {
        calls.add(new TapeRenderer.Call(++seq, tool, args, "OK", result));
    }

    void failed(String tool, String args, String result) {
        calls.add(new TapeRenderer.Call(++seq, tool, args, "ERROR", result));
    }

    /** What a click comes back with: the line it ran, and nothing resolvable. */
    static String ran(String code) {
        return "### Ran Playwright code\n```js\n" + code + "\n```\n### Page\n- Page URL: x";
    }

    static String tree(String yaml) {
        return "### Page\n- Page URL: x\n### Snapshot\n```yaml\n" + yaml + "\n```";
    }

    void login() {
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

    void reachTheScreen() {
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
}
