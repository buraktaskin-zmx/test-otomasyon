package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A form filled during a recorded walk, turned back into steps: dropdowns
 * however the agent opened them, dates however it set them, amounts typed into
 * two boxes, the screen's order, and submits pressed too early.
 *
 * Split out of TapeRendererTest unchanged.
 */
class TapeRendererFormTest extends TapeFixture {

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
        // The label is NOT in the same container as the box, so no structure
        // says which field this is: only its neighbour in reading order does.
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=a1]:
                  - generic [ref=a2]: Orijinal Fatura Tutarı
                - generic [ref=a9]:
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

    /**
     * Tape of 26 Oct, 16:4x. The real page wraps a Select2 in a container of
     * its own, so the label's next sibling is NOT the combobox but holds it.
     * Requiring the direct neighbour rejected the agent's own correct name
     * for the control, and with no snapshot after the choice (the agent went
     * straight on) the dropdown was either called "Kurum" or not written.
     */
    @Test
    @DisplayName("a label whose box sits in a wrapper still names its dropdown")
    void namesADropdownWhoseBoxIsWrapped() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=w1]:
                  - generic [ref=w2]: Fatura Tipi
                  - generic [ref=w3]:
                    - combobox [ref=w4]:
                      - textbox "Seçiniz" [ref=w5]"""));
        calls.add(new TapeRenderer.Call(++seq, "browser_click",
                "{\"target\": \"w4\", \"element\": \"Fatura Tipi combobox\"}", "OK",
                ran("await page.locator('#select2-billType-container').click();")));
        ok("browser_click", "{\"target\": \"w9\", \"element\": \"Normal\"}",
                ran("await page.getByRole('option', { name: 'Normal' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=k1]:
                  - generic [ref=k2]: Kurum
                  - generic [ref=k3]: Normal"""));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("select \"Normal\" from \"Fatura Tipi\"")
                .noneMatch(step -> step.contains("\"Kurum\""));
    }

    /**
     * Read from the real tape of 26 Oct, 17:0x: after the choice the SAME page
     * still shows the value inside Firma Seçimi's own box, and the page after
     * it prints "Kurum: Sanko DBS Ödemeleri" as plain text. Snapshots are read
     * newest first, so the plain text won.
     */
    @Test
    @DisplayName("the control holding the value names the field, not plain text on a later page")
    void prefersTheControlOverPlainText() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e368]:
                  - generic [ref=f1e369]: Firma Seçimi
                  - combobox [ref=f1e372]:
                    - textbox "Seçiniz" [ref=f1e373]"""));
        ok("browser_click", "{\"target\": \"f1e373\"}",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"f1e530\"}",
                ran("await page.getByRole('option', { name: 'Sanko DBS Ödemeleri' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e368]:
                  - generic [ref=f1e369]: Firma Seçimi
                  - combobox [ref=f1e554]:
                    - textbox "Sanko DBS Ödemeleri" [ref=f1e555]"""));
        ok("browser_click", "{\"target\": \"f1e421\"}",
                ran("await page.getByRole('link', { name: 'Fatura Oluştur' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e627]:
                  - generic [ref=f1e628]: Kurum
                  - generic [ref=f1e629]: Sanko DBS Ödemeleri"""));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("select \"Sanko DBS Ödemeleri\" from \"Firma Seçimi\"")
                .noneMatch(step -> step.contains("\"Kurum\""));
    }

    /**
     * The agent called the control something that is not exactly its label
     * ("Firma Seçimi dropdown kutusu"), and no snapshot after the choice shows
     * the value in a box: the walk went straight to the next screen. Neither
     * the agent's words nor the effect name the field — the box the agent
     * opened does, by sitting inside what the label "Firma Seçimi" introduces.
     */
    @Test
    @DisplayName("a dropdown nobody named is named by the label that owns its box")
    void namesADropdownByStructure() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e368]:
                  - generic [ref=f1e369]: Firma Seçimi
                  - combobox [ref=f1e372]:
                    - textbox "Seçiniz" [ref=f1e373]"""));
        calls.add(new TapeRenderer.Call(++seq, "browser_click",
                "{\"target\": \"f1e373\", \"element\": \"Firma listesi\"}", "OK",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();")));
        ok("browser_click", "{\"target\": \"f1e530\"}",
                ran("await page.getByRole('option', { name: 'Sanko DBS Ödemeleri' }).click();"));
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=f1e627]:
                  - generic [ref=f1e628]: Kurum
                  - generic [ref=f1e629]: Sanko DBS Ödemeleri"""));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("select \"Sanko DBS Ödemeleri\" from \"Firma Seçimi\"");
    }

    /** A decorated name still finds its label. */
    @Test
    @DisplayName("the agent's name only has to contain the label")
    void acceptsADecoratedName() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=g1]:
                  - generic [ref=g2]: Firma Seçimi
                  - combobox [ref=g3]:
                    - textbox "Seçiniz" [ref=g4]"""));
        calls.add(new TapeRenderer.Call(++seq, "browser_click",
                "{\"target\": \"g4\", \"element\": \"Firma Seçimi açılır kutusu\"}", "OK",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();")));
        ok("browser_click", "{\"target\": \"g9\"}",
                ran("await page.getByRole('option', { name: 'APPLE A.Ş.' }).click();"));

        assertThat(TapeRenderer.render(calls, MAIN_MENU, List.of()).steps())
                .contains("select \"APPLE A.Ş.\" from \"Firma Seçimi\"");
    }

    /** A calendar day whose box cannot be read is reported, never dropped silently. */
    @Test
    @DisplayName("a calendar day with no readable box is reported")
    void reportsAnUnreadableCalendarDay() {
        login();
        reachTheScreen();
        ok("browser_click", "{\"target\": \"f1e948\"}",
                ran("await page.getByRole('link', { name: '28' }).click();"));

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).noneMatch(step -> step.startsWith("click \"28\""));
        assertThat(rendered.notes()).anySatisfy(note -> assertThat(note).contains("28").contains("elle"));
    }

    /**
     * set_date makes its own browser calls, so its tape entry carries no
     * echoed Playwright line — only a box reference and a date argument.
     */
    @Test
    @DisplayName("a set_date call is an enter date")
    void rendersSetDate() {
        login();
        reachTheScreen();
        ok("browser_snapshot", "{}", tree("""
                - generic [ref=d1]:
                  - generic [ref=d2]: Fatura Tarihi
                  - textbox "Fatura Tarihi" [ref=d3]"""));
        ok("set_date", "{\"target\": \"d3\", \"date\": \"28/10/2026\"}",
                "Set to 28/10/2026. Clicking the day closes the calendar.");
        ok("set_date", "{\"target\": \"d3\", \"date\": \"31/02/abc\"}",
                "\"31/02/abc\" is not a date. Give it as gg/aa/yyyy.");

        TapeRenderer.Rendered rendered = TapeRenderer.render(calls, MAIN_MENU, List.of());

        assertThat(rendered.steps()).containsExactly(
                "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"",
                "enter date \"Fatura Tarihi\" \"28/10/2026\"");
        assertThat(rendered.notes()).anySatisfy(note -> assertThat(note).contains("set_date"));
    }
}
