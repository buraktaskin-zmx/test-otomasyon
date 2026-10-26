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
        // The effect: the chosen value now sits INSIDE its own control, which
        // is the only place the field can be named from reliably.
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e366]:
                  - generic [ref=f1e367]: Firma Seçimi
                  - combobox [ref=f1e552]:
                    - textbox "APPLE A.Ş." [ref=f1e553]"""));

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

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

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

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

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

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
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

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

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

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).notes())
                .anySatisfy(note -> assertThat(note).contains("BEKLENEN"));
    }

    /**
     * This application's menu fails intermittently — navigateTo retries it
     * three times for that reason — and when it does the agent dismisses the
     * error and starts over. Rendering from the FIRST entry wrote the failed
     * attempt into the scenario: a click on a screen that never opened,
     * followed by a dialog dismissal.
     */
    @Test
    @DisplayName("a failed attempt is dropped: the scenario starts at the LAST menu entry")
    void dropsAFailedAttempt() {
        login();
        // First attempt: menu, submenu, and the application refuses.
        ok("browser_snapshot", "{}", tree("- link \"NAKİT YÖNETİMİ\" [ref=f1e148]"));
        ok("browser_click", "{\"target\": \"f1e148\"}",
                ran("await page.getByRole('link', { name: 'NAKİT YÖNETİMİ' }).click();"));
        ok("browser_click", "{\"target\": \"f1e293\"}",
                ran("await page.getByRole('link', { name: 'Fatura Oluştur' }).click();"));
        ok("browser_click", "{\"target\": \"f1e391\"}",
                ran("await page.getByRole('button', { name: 'Tamam' }).click();"));
        // Second attempt, the one that worked.
        reachTheScreen();

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"");
        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("ana menüye").contains("başarısız"));
    }

    /**
     * On the replay there is no dialog, so a step waiting for "Tamam" spends
     * its whole timeout on a button that is not coming.
     */
    @Test
    @DisplayName("dismissing an error dialog is recovery, not a step")
    void dropsTheDialogDismissal() {
        login();
        reachTheScreen();
        ok("browser_click", "{\"target\": \"f1e500\"}",
                ran("await page.getByRole('button', { name: 'Tamam' }).click();"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).noneMatch(step -> step.contains("Tamam"));
        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("Tamam").contains("elle"));
    }

    /**
     * Climbing from an unopened combobox walks into the neighbouring field's
     * label. Measured on a DBS form: a currency came out as
     * `select "TL" from "Orijinal Fatura Tutarı"` — plausible, passes a
     * catalog check, and wrong. With no snapshot showing the effect, the line
     * is refused instead.
     */
    @Test
    @DisplayName("a choice whose field cannot be confirmed is refused, not guessed")
    void refusesAChoiceItCannotName() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=a1]:
                  - generic [ref=a2]: Orijinal Fatura Tutarı
                  - combobox [ref=a3]:
                    - textbox "Seçiniz" [ref=a4]"""));
        ok("browser_click", "{\"target\": \"a4\"}",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"a5\"}",
                ran("await page.getByRole('option', { name: 'TL' }).click();"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).noneMatch(step -> step.startsWith("select \"TL\""));
        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("TL").contains("elle"));
    }

    /**
     * The server writes `page.locator('#id').fill(…)` for a control it reached
     * by id. Reading only getByRole lost every `enter` of a form-filling walk
     * and reported each as "no vocabulary equivalent" — the step existed, the
     * parser could not see the call.
     */
    @Test
    @DisplayName("a fill without a role is still an enter, named from the snapshot")
    void recoversAFillWithoutARole() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=b1]:
                  - generic [ref=b2]: Fatura No
                  - textbox [ref=b3]"""));
        ok("browser_type", "{\"target\": \"b3\", \"text\": \"11113\"}",
                ran("await page.locator('#invoiceNo').fill('11113');"));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("enter \"Fatura No\" \"11113\"");
    }

    /**
     * A literal date written with plain `enter` is the mistake DateVerb
     * rewrites at generation time; the shape is known here, so the right verb
     * is written in the first place.
     */
    @Test
    @DisplayName("a date is entered with the date verb")
    void usesTheDateVerb() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=c1]:
                  - generic [ref=c2]: Fatura Tarihi
                  - textbox [ref=c3]"""));
        ok("browser_type", "{\"target\": \"c3\", \"text\": \"28/10/2026\"}",
                ran("await page.getByLabel('Fatura Tarihi').fill('28/10/2026');"));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("enter date \"Fatura Tarihi\" \"28/10/2026\"");
    }

    /**
     * "Oluşturma Tipi" is a fork between two screens, not a grid row: the
     * catalog calls it navigation and the vocabulary drives it with `choose`,
     * which exists precisely because selectRow matches on a table cell and
     * cannot see a radio in a plain form section. Reading every radio as a
     * row wrote no step at all for it.
     */
    @Test
    @DisplayName("a radio outside a grid is a choose, not a row")
    void readsAFormRadioAsAChoice() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=d1]:
                  - generic [ref=d2]: Oluşturma Tipi
                  - radio "Bilgi Girişi" [ref=d3]
                  - radio "Dosya Yükleme" [ref=d4]"""));
        ok("browser_click", "{\"target\": \"d3\"}",
                ran("await page.getByRole('radio', { name: 'Bilgi Girişi' }).click();"));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("choose \"Bilgi Girişi\"");
    }

    /** The same element inside a grid stays a row pick. */
    @Test
    @DisplayName("a radio inside a grid is still a row")
    void keepsAGridRadioAsARow() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - rowgroup [ref=e1]:
                  - row [ref=e2]:
                    - cell [ref=e3]:
                      - radio [ref=e4]
                    - cell "151732" [ref=e5]
                    - cell "ccsdf" [ref=e6]
                  - row [ref=e7]:
                    - cell "151732" [ref=e8]
                    - cell "11113" [ref=e9]"""));
        ok("browser_click", "{\"target\": \"e4\"}",
                ran("await page.getByRole('row').getByRole('radio').click();"));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("select row \"ccsdf\"");
    }

    /**
     * Measured on DBS Fatura Oluştur (tape fe25749f): "Devam" was pressed the
     * moment "Bilgi Girişi" revealed the form, the bank painted "giriniz"
     * under every required field, and the same "Devam" — same reference, so
     * the page never changed — was pressed again once the form was filled.
     */
    @Test
    @DisplayName("a submit pressed before the form was filled, then retried, is written once")
    void dropsTheEarlySubmit() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=d1]:
                  - generic [ref=d2]: Oluşturma Tipi
                  - radio "Bilgi Girişi" [ref=d3]
                - generic [ref=b1]:
                  - generic [ref=b2]: Fatura No
                  - textbox [ref=b3]
                - link "Devam" [ref=f1e753]"""));
        ok("browser_click", "{\"target\": \"d3\"}",
                ran("await page.getByRole('radio', { name: 'Bilgi Girişi' }).click();"));
        ok("browser_click", "{\"target\": \"f1e753\"}",
                ran("await page.getByRole('link', { name: 'Devam' }).click();"));
        ok("browser_type", "{\"target\": \"b3\", \"text\": \"11113\"}",
                ran("await page.getByRole('textbox', { name: 'Fatura No' }).fill('11113');"));
        ok("browser_click", "{\"target\": \"f1e753\"}",
                ran("await page.getByRole('link', { name: 'Devam' }).click();"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsSubsequence(
                "choose \"Bilgi Girişi\"", "enter \"Fatura No\" \"11113\"", "click \"Devam\"");
        assertThat(rendered.steps()).filteredOn(step -> step.equals("click \"Devam\"")).hasSize(1);
        assertThat(rendered.notes()).anySatisfy(note -> assertThat(note).contains("#").contains("Devam"));
    }

    /** "Fatura Oluştur" before a Kurum was chosen: dialog, Tamam, choose, again. */
    @Test
    @DisplayName("a button refused for an empty choice, then pressed again, is written once")
    void dropsTheButtonPressedBeforeTheChoice() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=k1]:
                  - generic [ref=k2]: Kurum
                  - combobox [ref=k3]:
                    - textbox "Seçiniz" [ref=k4]
                - link "Fatura Oluştur" [ref=f1e421]"""));
        ok("browser_click", "{\"target\": \"f1e421\"}",
                ran("await page.getByRole('link', { name: 'Fatura Oluştur' }).click();"));
        ok("browser_click", "{\"target\": \"f1e451\"}",
                ran("await page.getByRole('link', { name: 'Tamam' }).click();"));
        ok("browser_click", "{\"target\": \"k4\"}",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"f1e550\"}",
                ran("await page.getByRole('option', { name: 'Sanko DBS Ödemeleri' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=k1]:
                  - generic [ref=k2]: Kurum
                  - combobox [ref=k5]:
                    - textbox "Sanko DBS Ödemeleri" [ref=k6]
                - link "Fatura Oluştur" [ref=f1e421]"""));
        ok("browser_click", "{\"target\": \"f1e421\"}",
                ran("await page.getByRole('link', { name: 'Fatura Oluştur' }).click();"));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "select \"Sanko DBS Ödemeleri\" from \"Kurum\"",
                "click \"Fatura Oluştur\"");
    }

    /**
     * The server opens a Select2 it found no role for by id —
     * `page.locator('#select2-bayiKodu-container').click()` — and skipping
     * that line left the option with no trigger: `click "bayisanko1"`.
     */
    @Test
    @DisplayName("a dropdown opened by id is still a select")
    void readsADropdownOpenedById() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=g1]:
                  - generic [ref=g2]: Bayi Kodu
                  - combobox [ref=g3]:
                    - textbox [ref=g4]"""));
        ok("browser_click", "{\"target\": \"g4\"}",
                ran("await page.locator('#select2-bayiKodu-container').click();"));
        ok("browser_click", "{\"target\": \"g9\"}",
                ran("await page.getByRole('option', { name: 'bayisanko1' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=g1]:
                  - generic [ref=g2]: Bayi Kodu
                  - combobox [ref=g5]:
                    - textbox "bayisanko1" [ref=g6]"""));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).contains("select \"bayisanko1\" from \"Bayi Kodu\"");
        assertThat(rendered.steps()).noneMatch(step -> step.startsWith("click"));
    }

    /** Focus before typing is not a step: `click` cannot drive a text box. */
    @Test
    @DisplayName("clicking into a box before typing is not written")
    void dropsTheFocusClick() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=c1]:
                  - generic [ref=c2]: Başlangıç Vadesi
                  - textbox "Başlangıç Vadesi" [ref=c3]"""));
        ok("browser_click", "{\"target\": \"c3\"}",
                ran("await page.getByRole('textbox', { name: 'Başlangıç Vadesi' }).click();"));
        ok("browser_type", "{\"target\": \"c3\", \"text\": \"27/10/2026\"}",
                ran("await page.getByRole('textbox', { name: 'Başlangıç Vadesi' }).fill('27/10/2026');"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "enter date \"Başlangıç Vadesi\" \"27/10/2026\"");
        assertThat(rendered.notes()).noneMatch(note -> note.contains("metin kutusuna"));
    }

    /**
     * An amount is two boxes, typed key by key as the agent is told to; the
     * kuruş box is reached by id. Read as one `enter amount`, which splits it
     * back the same way.
     */
    @Test
    @DisplayName("lira and kuruş typed separately become one enter amount")
    void joinsTheTwoAmountBoxes() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=t1]:
                  - generic [ref=t2]: Toplam Tutar
                  - textbox "Toplam Tutar" [ref=t3]
                  - textbox [ref=t4]
                  - generic [ref=t5]: TL"""));
        ok("browser_type", "{\"target\": \"t3\", \"text\": \"1000\", \"slowly\": true}",
                ran("await page.getByRole('textbox', { name: 'Toplam Tutar' }).pressSequentially('1000');"));
        ok("browser_type", "{\"target\": \"t4\", \"text\": \"50\", \"slowly\": true}",
                ran("await page.locator('#toplamTutar-decimal').pressSequentially('50');"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "enter amount \"Toplam Tutar\" \"1000,50\"");
        assertThat(rendered.notes()).noneMatch(note -> note.contains("karşılık yok"));
    }

    /**
     * The second Fatura Oluştur walk (tape of 26 Oct, 15:2x). The agent typed
     * into a Select2's search box and re-opened it before choosing, mangled a
     * masked date, was refused by "Devam", corrected the date through the
     * calendar and pressed the SAME "Devam" again — with nothing typed in
     * between, so only the refused page still carrying the link proves the
     * first press failed.
     */
    @Test
    @DisplayName("a Select2 typed into, a refused submit and a calendar fix come out clean")
    void rendersAWanderingWalk() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e630]:
                  - generic [ref=f1e631]: Bayi Kodu
                  - combobox [ref=f1e634]:
                    - textbox "Seçiniz" [ref=f1e635]
                - generic [ref=f1e690]:
                  - generic [ref=f1e691]: Fatura Tarihi
                  - textbox "Fatura Tarihi" [ref=f1e692]
                  - img "..." [ref=f1e693]
                - link "Devam" [ref=f1e733]"""));
        // Open, type into the search box, open again, open again, choose.
        ok("browser_click", "{\"target\": \"f1e634\"}",
                ran("await page.locator('#s2id_bayiKodu').getByRole('combobox', { name: 'Seçiniz' }).click();"));
        ok("browser_type", "{\"target\": \"f1e635\", \"text\": \"151732\", \"slowly\": true}",
                ran("await page.locator('#select2-bayiKodu-container').pressSequentially('151732');"));
        ok("browser_click", "{\"target\": \"f1e634\"}",
                ran("await page.locator('#s2id_bayiKodu').getByRole('combobox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"f1e759\"}",
                ran("await page.getByRole('option', { name: 'bayisanko1' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e630]:
                  - generic [ref=f1e631]: Bayi Kodu
                  - combobox [ref=f1e760]:
                    - textbox "bayisanko1" [ref=f1e761]
                - generic [ref=f1e690]:
                  - generic [ref=f1e691]: Fatura Tarihi
                  - textbox "Fatura Tarihi" [ref=f1e692]
                  - img "..." [ref=f1e693]
                - link "Devam" [ref=f1e733]"""));
        ok("browser_press_key", "{\"key\": \"Tab\"}", ran("await page.keyboard.press('Tab');"));
        ok("browser_type", "{\"target\": \"f1e692\", \"text\": \"28/10/2026\", \"slowly\": true}",
                ran("await page.getByRole('textbox', { name: 'Fatura Tarihi' }).pressSequentially('28/10/2026');"));
        // First press: the form refuses and the same link stays on the page.
        ok("browser_click", "{\"target\": \"f1e733\"}",
                ran("await page.getByRole('link', { name: 'Devam' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e690]:
                  - generic [ref=f1e691]: Fatura Tarihi
                  - textbox "Fatura Tarihi" [ref=f1e692]: 81/02/0262
                  - generic [ref=f1e1082]: Geçerli bir tarih giriniz.
                - link "Devam" [ref=f1e733]"""));
        ok("browser_press_key", "{\"key\": \"Escape\"}", ran("await page.keyboard.press('Escape');"));
        ok("browser_click", "{\"target\": \"f1e692\"}",
                ran("await page.getByRole('textbox', { name: 'Fatura Tarihi' }).click();"));
        failed("browser_snapshot", "{\"target\": \"f1e1091\"}", "Error: Ref f1e1091 not found");
        ok("browser_press_key", "{\"key\": \"Delete\"}", ran("await page.keyboard.press('Delete');"));
        ok("browser_click", "{\"target\": \"f1e693\"}",
                ran("await page.locator('#billDateFC').getByRole('img', { name: '...' }).click();"));
        ok("browser_click", "{\"target\": \"f1e1258\"}",
                ran("await page.getByRole('link', { name: '28' }).click();"));
        ok("browser_click", "{\"target\": \"f1e733\"}",
                ran("await page.getByRole('link', { name: 'Devam' }).click();"));
        ok("browser_snapshot", "{}", tree("- heading \"Onay\" [ref=z1]"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "select \"bayisanko1\" from \"Bayi Kodu\"",
                "enter date \"Fatura Tarihi\" \"28/10/2026\"",
                "click \"Devam\"");
        assertThat(rendered.notes()).noneMatch(note -> note.contains("error")
                || note.contains("hata oldu") || note.contains("oldu, adıma"));
        assertThat(rendered.notes()).filteredOn(note -> note.contains("klavye")).hasSize(1);
    }

    /**
     * The choice changes the page, so the first snapshot AFTER it is the next
     * screen, where the same text is a static "Kurum: Sanko DBS Ödemeleri".
     * The dropdown came out as `from "Kurum"`, a word that names no control;
     * the real label, "Firma Seçimi", is what the agent itself called it.
     */
    @Test
    @DisplayName("a dropdown is named by its own label, not by static text on the next screen")
    void namesADropdownByItsLabel() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e366]:
                  - generic [ref=f1e367]: Firma Seçimi
                  - combobox [ref=f1e370]:
                    - textbox "Seçiniz" [ref=f1e371]"""));
        calls.add(new TapeRenderer.Call(++seq, "browser_click",
                "{\"target\": \"f1e370\", \"element\": \"Firma Seçimi combobox\"}", "OK",
                ran("await page.getByRole('combobox', { name: 'Seçiniz' }).click();")));
        ok("browser_click", "{\"target\": \"f1e530\"}",
                ran("await page.getByRole('option', { name: 'Sanko DBS Ödemeleri' }).click();"));
        ok("browser_click", "{\"target\": \"f1e421\"}",
                ran("await page.getByRole('link', { name: 'Fatura Oluştur' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e626]:
                  - generic [ref=f1e627]:
                    - generic [ref=f1e628]: Kurum
                    - generic [ref=f1e629]: Sanko DBS Ödemeleri
                  - generic [ref=f1e630]:
                    - generic [ref=f1e631]: Bayi Kodu
                    - combobox [ref=f1e760]:
                      - textbox "Seçiniz" [ref=f1e761]"""));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("select \"Sanko DBS Ödemeleri\" from \"Firma Seçimi\"")
                .noneMatch(step -> step.contains("\"Kurum\""));
    }

    /**
     * One walk chose "Döviz Cinsi" last and, because a currency change clears
     * the amounts, typed them twice. The scenario should read the way the
     * screen is laid out: top to bottom, each field once, last value kept.
     */
    @Test
    @DisplayName("a form is written top to bottom, each field once")
    void writesTheFormInScreenOrder() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=s1]:
                  - generic [ref=s2]: Fatura No
                  - textbox "Fatura No" [ref=s3]
                - generic [ref=s4]:
                  - generic [ref=s5]: Döviz Cinsi
                  - combobox [ref=s6]:
                    - textbox "Seçiniz" [ref=s7]
                - generic [ref=s8]:
                  - generic [ref=s9]: Taksit Adedi
                  - textbox "Taksit Adedi" [ref=s10]"""));
        ok("browser_type", "{\"target\": \"s10\", \"text\": \"12\"}",
                ran("await page.getByRole('textbox', { name: 'Taksit Adedi' }).fill('12');"));
        ok("browser_type", "{\"target\": \"s3\", \"text\": \"11113\"}",
                ran("await page.getByRole('textbox', { name: 'Fatura No' }).fill('11113');"));
        ok("browser_click", "{\"target\": \"s7\", \"element\": \"Döviz Cinsi combobox\"}",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"s20\"}",
                ran("await page.getByRole('option', { name: 'TL' }).click();"));
        ok("browser_type", "{\"target\": \"s10\", \"text\": \"24\"}",
                ran("await page.getByRole('textbox', { name: 'Taksit Adedi' }).fill('24');"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "enter \"Fatura No\" \"11113\"",
                "select \"TL\" from \"Döviz Cinsi\"",
                "enter \"Taksit Adedi\" \"24\"");
        assertThat(rendered.notes()).anySatisfy(note -> assertThat(note).contains("yukarıdan aşağıya"));
    }

    /**
     * Tape of 26 Oct, 15:5x. The agent reached "Bayi Kodu" through five
     * different references — its container, its inner box, its search box —
     * and chose the option only on the last try. Judging "the same trigger"
     * by reference left a stray `click "Seçiniz"` in the scenario.
     */
    @Test
    @DisplayName("a dropdown re-opened through other references is still one select")
    void followsADropdownReachedByManyReferences() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e630]:
                  - generic [ref=f1e631]: Bayi Kodu
                  - combobox [ref=f1e634]:
                    - textbox "Seçiniz" [ref=f1e635]"""));
        calls.add(new TapeRenderer.Call(++seq, "browser_click",
                "{\"target\": \"f1e635\", \"element\": \"Bayi Kodu combobox\"}", "OK",
                ran("await page.locator('#select2-bayiKodu-container').click();")));
        failed("browser_type", "{\"target\": \"f1e635\", \"text\": \"151732\"}", "TimeoutError");
        ok("browser_click", "{\"target\": \"f1e634\"}",
                ran("await page.locator('#s2id_bayiKodu').getByRole('combobox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"f1e635\"}",
                ran("await page.locator('#select2-bayiKodu-container').click();"));
        ok("browser_type", "{\"target\": \"f1e747\", \"text\": \"151732\"}",
                ran("await page.getByRole('searchbox').fill('151732');"));
        failed("browser_click", "{\"target\": \"f1e758\"}", "Ref f1e758 not found");
        ok("browser_press_key", "{\"key\": \"Escape\"}", ran("await page.keyboard.press('Escape');"));
        ok("browser_click", "{\"target\": \"f1e635\"}",
                ran("await page.locator('#select2-bayiKodu-container').click();"));
        ok("browser_click", "{\"target\": \"f1e769\"}",
                ran("await page.getByRole('option', { name: 'bayisanko1' }).click();"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "select \"bayisanko1\" from \"Bayi Kodu\"");
    }

    /**
     * The agent never typed "Başlangıç Vadesi": it opened the calendar, the
     * form refused the submit, and it clicked the day 27 afterwards. The only
     * place the date exists is that click, and the box it filled says so.
     */
    @Test
    @DisplayName("a date set only by clicking a calendar day is still written")
    void readsADateSetThroughTheCalendar() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=c1]:
                  - generic [ref=c2]: Başlangıç Vadesi
                  - textbox "Başlangıç Vadesi" [ref=f1e643]
                  - img "..." [ref=f1e644]"""));
        ok("browser_click", "{\"target\": \"f1e644\"}",
                ran("await page.locator('#baslangicVadesiFC').getByRole('img', { name: '...' }).click();"));
        ok("browser_click", "{\"target\": \"f1e1083\"}",
                ran("await page.getByRole('link', { name: '27' }).click();"));
        ok("browser_snapshot", "{\"target\": \"f1e641\", \"depth\": 5}", tree("""
                - generic [ref=c1]:
                  - generic [ref=c2]: Başlangıç Vadesi
                  - textbox "Başlangıç Vadesi" [ref=f1e643]: 27/10/2026"""));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps()).contains(
                "enter date \"Başlangıç Vadesi\" \"27/10/2026\"");
    }

    /** The detail dialog, label and value as siblings in one cell. */
    private void detailDialog() {
        ok("browser_snapshot", "{}", tree("""
                - dialog [ref=d1]:
                  - row [ref=r1]:
                    - cell "Bayi Kodu 151732" [ref=c1]:
                      - generic [ref=g1]:
                        - generic [ref=g2]: Bayi Kodu
                        - generic [ref=g3]: "151732"
                  - row [ref=r2]:
                    - cell "Vade Tarihi 19/10/2026" [ref=c2]:
                      - generic [ref=g4]:
                        - generic [ref=g5]: Vade Tarihi
                        - generic [ref=g6]: 19/10/2026
                  - row [ref=r3]:
                    - cell "Fatura Tutarı 100,00 TL" [ref=c3]:
                      - generic [ref=g7]:
                        - generic [ref=g8]: Fatura Tutarı
                        - generic [ref=g9]: 100,00 TL"""));
    }

    /**
     * The THEN is the analyst's, and the verb is the value's.
     *
     * The third expectation is written without Turkish characters on purpose:
     * that is how they arrive, and this walk's own BEKLENEN line says
     * "oldugunu dogrula".
     */
    @Test
    @DisplayName("the expectation becomes a verify, with the verb its value asks for")
    void derivesVerifiesFromTheExpectation() {
        login();
        reachTheScreen();
        detailDialog();

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of(
                "Bayi Kodu 151732 oldugunu doğrula",
                "Vade Tarihi 19/10/2026 olmalı",
                "Fatura Tutari 100,00 TL olmali"));

        assertThat(rendered.steps()).contains(
                "verify the \"Bayi Kodu\" field equals \"151732\"",
                "verify the \"Vade Tarihi\" has date \"19/10/2026\"",
                "verify the \"Fatura Tutarı\" has amount \"100,00 TL\"");
    }

    /**
     * A verify naming a field the walk never saw would spend its whole
     * timeout and blame the wrong thing. That is knowable here.
     */
    @Test
    @DisplayName("an expectation about a field nobody saw is refused, not guessed")
    void refusesAnExpectationWithNoFieldOnScreen() {
        login();
        reachTheScreen();
        detailDialog();

        TapeRenderer.Rendered rendered =
                TapeRenderer.render(calls, MAIN_MENU, List.of("Garanti Tutarı 5,00 TL olmalı"));

        assertThat(rendered.steps()).noneMatch(step -> step.startsWith("verify"));
        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("Garanti Tutarı").contains("elle"));
    }

    /**
     * The analyst's value wins and the difference is reported. Taking the
     * screen's instead would write a live defect into the suite as a passing
     * test — the one thing an oracle must never do.
     */
    @Test
    @DisplayName("an expectation that disagrees with the screen keeps the analyst's value and says so")
    void keepsTheAnalystsValueAndReportsTheDisagreement() {
        login();
        reachTheScreen();
        detailDialog();

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU,
                List.of("Bayi Kodu 999999 oldugunu doğrula"));

        assertThat(rendered.steps()).contains("verify the \"Bayi Kodu\" field equals \"999999\"");
        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("999999").contains("151732"));
    }

    /**
     * A walk that never reached the screen renders nothing, and says that
     * rather than handing back an empty scenario.
     */
    @Test
    @DisplayName("a walk that never reached the menu renders nothing and says so")
    void reportsAWalkThatWentNowhere() {
        login();

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).isEmpty();
        assertThat(rendered.notes()).anySatisfy(note ->
                assertThat(note).contains("hiçbir adım"));
    }
}
