package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.ykb.uplift.uptest.playwright.common.DatePicker.isDatePicker;
import static com.ykb.uplift.uptest.playwright.common.DatePicker.valueTook;

/**
 Application-independent Playwright logic shared by NGC and NGI.

 Everything here derives locators from labels/attributes/roles/text, no
 specific element ids or login flows. The page-object classes (NGC, NGI)
 reuse this for their internal generic actions (click, navigate); the
 generic step definitions live in {@link CommonSteps}.

 NOTE: findValueElement is currently the NGC structure
 (fieldcontainer / elem-span-infos)
 */
public class CommonActions {

    private final Page page;

    // The parts this class hands work to, each one a purpose of its own.
    private final DatePicker datePicker;
    private final ErrorDialogs dialogs;
    private final FailureExplanations explain;
    private final ValueFields fields;
    private final Verifications verifications;
    private final RadioActions radios;
    private final Uploads uploads;
    private final Dropdowns dropdowns;

    public CommonActions(Page page) {
        this.page = page;
        this.datePicker = new DatePicker(page);
        this.dialogs = new ErrorDialogs(page, datePicker);
        this.explain = new FailureExplanations(page);
        this.fields = new ValueFields(page, dialogs);
        this.verifications = new Verifications(page, fields, explain);
        this.radios = new RadioActions(page, dialogs);
        this.uploads = new Uploads(page, dialogs);
        this.dropdowns = new Dropdowns(page, fields);
    }

    private static final String DIALOG_DISMISS = ErrorDialogs.DIALOG_DISMISS;
    private static final double SELECT_CONFIRM_MS = Dropdowns.SELECT_CONFIRM_MS;

    /** Texts the scenario goes on to assert; see {@link TestContext#expectedTexts}. */
    private Set<String> expectedTexts = Set.of();

    public CommonActions expecting(Set<String> texts) {
        this.expectedTexts = texts == null ? Set.of() : texts;
        return this;
    }

    /**
     * The line a form paints under a field it refuses: "Fatura tarihi
     * giriniz.", "Başlangıç vadesi seçiniz.", "Geçerli bir tarih giriniz.".
     * Anchored on the whole text and on the full stop, so the "Seçiniz"
     * placeholder of every dropdown never matches.
     */
    private static final Pattern FORM_REFUSAL = Pattern.compile(
            "^\\s*\\S.{2,160}\\s(giriniz|seçiniz)\\.\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** The refusal lines visible on the page right now. Never throws. */
    private List<String> formRefusals() {
        try {
            return page.getByText(FORM_REFUSAL)
                    .filter(new Locator.FilterOptions().setVisible(true))
                    .allInnerTexts().stream()
                    .map(String::trim)
                    .distinct()
                    .toList();
        } catch (RuntimeException navigating) {
            // The click may have started a navigation; a page in flight has
            // nothing to refuse.
            return List.of();
        }
    }

    /**
     * Refusal lines that appeared because of the click — checked at once,
     * since the form validates inside the click handler, and once more a
     * moment later for one that animates in.
     */
    private List<String> refusalsCausedSince(List<String> before) {
        List<String> fresh = new ArrayList<>(formRefusals());
        fresh.removeAll(before);
        if (fresh.isEmpty()) {
            page.waitForTimeout(REFUSAL_GRACE_MS);
            fresh = new ArrayList<>(formRefusals());
            fresh.removeAll(before);
        }
        return fresh;
    }

    private static final double REFUSAL_GRACE_MS = 400;

    /** Whether the scenario goes on to assert one of these lines. */
    private boolean expected(List<String> refusals) {
        for (String refusal : refusals) {
            String shown = refusal.toLowerCase(Locale.forLanguageTag("tr-TR"));
            for (String text : expectedTexts) {
                String wanted = text.toLowerCase(Locale.forLanguageTag("tr-TR")).trim();
                if (!wanted.isEmpty() && (shown.contains(wanted) || wanted.contains(shown))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ======================= GENERIC ACTIONS ===============================

    public CommonActions click(String label) {
        closeDatePicker();
        // While a modal is up, its overlay swallows every click on the page
        // behind it — Playwright then retries for 30 s and reports a timeout on
        // an element that was perfectly visible, which says nothing about the
        // real cause. So when a dialog is open, only the dialog is clickable:
        // "Tamam" still works (that is how a negative scenario dismisses the
        // error), and anything else fails immediately with the dialog's text.
        Locator dialog = visibleDialog();
        if (dialog != null) {
            Locator inDialog = dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(label))
                    .or(dialog.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(label)));
            if (inDialog.count() > 0 && inDialog.first().isVisible()) {
                inDialog.first().click();
                return this;
            }
            throw new AssertionError(
                    "Acik bir diyalog varken '" + label + "' tiklanamaz: " + dialogMessage(dialog)
                            + " | Once diyalogu kapatin: click \"" + DIALOG_DISMISS + "\".");
        }

        // Buttons and links together, in DOM order. Preferring buttons over
        // links was tried and reverted: "Fatura Oluştur" on the Online DBS list
        // is a LINK, and preferring a button sent the click somewhere else —
        // the step went green, the form never opened, and the next step blamed
        // "Bayi Kodu". Ambiguity is reported below instead of guessed at.
        Locator target = byName(label, false);

        // An EXACT name wins when one exists, because getByRole's default is a
        // SUBSTRING match and these screens put the short caption inside the
        // long one. On Firma Fatura İşlemleri the page carries "Detaylı Arama"
        // and "Detay": the loose match finds both, and "Detaylı Arama" comes
        // first in document order, so `click "Detay"` opened the search panel
        // and every step after it failed somewhere else. Read off a recorded
        // agent walk, where the MCP server disambiguated the same click with
        // `getByRole('link', { name: 'Detay', exact: true })` — the option our
        // step had never passed.
        Locator exact = byName(label, true);
        if (exact.count() == 0 && target.count() > 1) {
            // Only pay for this when there IS ambiguity: the panel holding the
            // short caption is often the one that just re-rendered, so it can
            // be a moment behind the long one.
            try {
                exact.first().waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.ATTACHED).setTimeout(EXACT_NAME_MS));
            } catch (RuntimeException neverArrived) {
                // No exact caption on this screen; the loose match is all there is.
            }
        }
        if (exact.count() > 0) {
            target = exact;
        }

        if (target.count() > 1) {
            System.out.println("[uptest] click: '" + label + "' icin " + target.count()
                    + " aday var, ilki tiklaniyor -> "
                    + String.join(" | ", target.allInnerTexts()));
        }

        // A dropdown is not a button or a link, so `click "Firma Seçimi"` found
        // nothing to press and spent 30 s proving it — while a person simply
        // clicks the box and the list opens. Only reached when NO button or
        // link carries the name right now AND the name labels a Select2 field,
        // so every click that worked before still takes the path below.
        //
        // WAITED FOR, never asked once. `navigate to` returns the moment it has
        // clicked the menu link, so this step can start while the next page is
        // still arriving — and count() answers about the DOM as it stands that
        // instant. Asked too early it said "no such dropdown", the step fell
        // through to the 30 s button search, and by the time the failure
        // listed the screen's buttons the page had long arrived: a message
        // that read like "right page, wrong word". So first wait for EITHER a
        // button/link with the name OR the label's own text; a button that
        // turns up ends the wait at once and is clicked as it always was.
        if (target.count() == 0) {
            awaitButtonOrLabel(label, target);
            boolean labelOnPage = page.getByText(label, new Page.GetByTextOptions().setExact(true)).count() > 0;
            if (target.count() == 0 && labelOnPage && awaitSelect2Field(label)) {
                openSelect2(label);
                System.out.println("[uptest] click: '" + label + "' bir acilir liste; liste acildi");
                return this;
            }
        }

        List<String> before = formRefusals();
        try {
            target.first().click();
        } catch (RuntimeException failed) {
            // Only an ABSENT target earns a candidate list. A click that found
            // its element and was then blocked - an overlay intercepting the
            // pointer, a disabled button - is a different failure, and listing
            // the other buttons on the page there would be noise on top of a
            // message that was already correct.
            if (target.count() == 0) {
                throw missingClickTarget(label, failed);
            }
            throw failed;
        }

        // A click is not finished when the button was pressed; it is finished
        // when the press DID something. "Devam" on DBS Fatura Oluştur was
        // pressed with both dates empty, the form painted "… giriniz." and
        // stayed where it was — and the step went green, the verifies after it
        // read the SAME form's boxes and passed too, and the run failed four
        // steps later on a date nobody had looked at. A refusal the scenario
        // goes on to assert (a negative scenario checking "11 karakter
        // uzunluğunda bir değer giriniz.") is the expected outcome and passes.
        List<String> refused = refusalsCausedSince(before);
        if (!refused.isEmpty()) {
            if (expected(refused)) {
                System.out.println("[uptest] click: '" + label + "' formu beklenen sekilde reddetti: "
                        + String.join(" | ", refused));
            } else {
                throw new AssertionError("'" + label + "' tiklandi ama form GONDERILMEDI; ekran sunu"
                        + " yaziyor: " + String.join(" | ", refused)
                        + " — bu alanlar bos ya da gecersiz. Beklenen bir hataysa senaryoda"
                        + " dogrulayin: verify \"<mesaj>\" text is displayed");
            }
        }
        return this;
    }

    /**
     * Buttons and links carrying this accessible name.
     *
     * @param exact true asks Playwright for the WHOLE name rather than its
     *              default substring match, which is the difference between
     *              "Detay" and "Detaylı Arama"
     */
    private Locator byName(String label, boolean exact) {
        return page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(label).setExact(exact))
                .or(page.getByRole(AriaRole.LINK,
                        new Page.GetByRoleOptions().setName(label).setExact(exact)));
    }

    /**
     * How long an ambiguous click waits for the exact caption to turn up.
     *
     * Short on purpose: it is only ever spent when the loose match already
     * found more than one candidate, and the panel holding the short caption
     * has usually just re-rendered rather than being absent.
     */
    private static final double EXACT_NAME_MS = 3000;

    public CommonActions navigateToUrl(String url) {
        Page.NavigateOptions options = new Page.NavigateOptions();
        options.setTimeout(1000);
        try {
            page.navigate(url, options);
        } catch (Exception e) {

        }
        return this;
    }

    /**
     * Which element took the value, and what it holds as the step ends.
     *
     * A date "entered" on DBS Fatura Oluştur read back as entered inside the
     * step and as "" four steps later, on #billDate. Two different things
     * explain that — the label resolved to a DIFFERENT element than the one
     * the form submits, or something cleared the right one afterwards — and
     * only the element's id tells them apart. One line per enter, no wait.
     */
    private static void logEntered(String label, Locator field, String how) {
        try {
            System.out.println("[uptest] enter: '" + label + "' -> #" + field.getAttribute("id")
                    + " (" + how + ") = '" + field.inputValue() + "'");
        } catch (RuntimeException unreadable) {
            // Logging must never fail the step it describes.
        }
    }

    public CommonActions enter(String label, String value) {
        failFastOnErrorDialog("enter");

        // Typing into a dropdown's SEARCH box, once a click has opened it —
        // `click "Firma Seçimi"` then `enter "Firma Seçimi" "san"`. Only while
        // the list is open: a lone `enter` on a dropdown still fails as before,
        // because writing a search without choosing anything would otherwise
        // let a scenario that meant to SELECT go green with nothing selected.
        Locator search = openSelect2Search(label);
        if (search != null) {
            search.fill("");
            search.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(TYPE_DELAY_MS));
            assertThat(search).hasValue(value,
                    new LocatorAssertions.HasValueOptions().setTimeout(SELECT_CONFIRM_MS));
            System.out.println("[uptest] enter: '" + label + "' arama kutusuna '" + value + "' yazildi");
            failFastOnErrorDialog("enter (sonrasi)");
            return this;
        }
        // The same dropdown, list closed: refused, as designed — but by name.
        // Left to fill(), Playwright found the hidden native <select> behind
        // the widget and said "Element is not an <input>", which names neither
        // the cause nor either of the two ways out.
        if (isSelect2Field(label)) {
            throw new AssertionError("'" + label + "' bir acilir liste; icine dogrudan yazilamaz."
                    + " ARAMAK icin once listeyi acin: click \"" + label + "\" ve hemen ardindan"
                    + " enter \"" + label + "\" \"" + value + "\"."
                    + " SECMEK icin: select \"<secenek>\" from \"" + label + "\".");
        }

        Locator field = page.getByLabel(label);

        // A jQuery UI datepicker field is driven through its calendar, not by
        // writing into the box: the box wears a mask that only accepts key
        // events, and the calendar is what a user actually uses. Same reasoning
        // as the Select2 lesson. Falls through to typing when the value is not
        // a date, or when the calendar does not appear.
        //
        // Both calls below resolve the locator, so an absent label surfaces
        // here as Playwright's 30 s "element(s) not found" - which names
        // neither the cause nor the fix. AssertionError passes through
        // untouched: pickFromCalendar throws one when the calendar DID open
        // and the value still did not stick, and that message is already the
        // right one.
        try {
            if (isDatePicker(field) && pickFromCalendar(label, field, value)) {
                logEntered(label, field, "takvim");
                failFastOnErrorDialog("enter (sonrasi)");
                return this;
            }
            field.fill(value);
        } catch (RuntimeException failed) {
            if (field.count() == 0) {
                throw missingField(label, failed);
            }
            throw failed;
        }

        // fill() writes the whole value in one `input` event. An input wearing a
        // jQuery mask ("__/__/____") listens for key events and rebuilds the
        // value character by character, so it does not recognise that one event
        // and paints the empty mask back — while fill() reports success and the
        // step goes green with the field still blank. Seen on DBS Fatura
        // Oluştur: "Başlangıç Vadesi" and "Fatura Tarihi" stayed empty.
        //
        // Typing is not the default because it is slower and fill() is correct
        // for every ordinary input; it is the fallback for when the field
        // demonstrably did not take the value.
        if (!valueTook(field, value)) {
            typeInto(field, value);
            if (!valueTook(field, value)) {
                throw new AssertionError("'" + label + "' alanina '" + value
                        + "' yazilamadi. Alandaki deger: '" + field.inputValue() + "'");
            }
        }

        closeDatePicker();
        logEntered(label, field, "yazma");
        // Checked again afterwards: these forms validate against the server on
        // blur, so the dialog this very step provoked would otherwise be
        // reported against the NEXT step — or against none at all, when this is
        // the last field before a click.
        failFastOnErrorDialog("enter (sonrasi)");
        return this;
    }

    // Top menu navigation: open the parent menu item, then click the child link.
    // NOTE: bound to the #headerMenuUsername / .main-nav header structure —
    // shared on the assumption NGC and NGI render the same menu markup.
    public CommonActions navigateTo(String parentMenuText, String child) {
        failFastOnErrorDialog("navigate to");
        page.waitForSelector("#headerMenuUsername");

        // The submenu opens asynchronously and the same caption also sits in
        // collapsed sections, so snapshotting with .all() could freeze the DOM
        // before the menu opened and hand back a hidden link — Playwright then
        // retried a click on it for 30 s and blamed an element that was never
        // going to be clickable. Asking for the visible one by exact text lets
        // Playwright wait for the right link instead.
        //
        // :text-is is Playwright's own exact-text selector, and it is used here
        // rather than a java.util.regex Pattern because Playwright hands the
        // pattern to the BROWSER: Pattern.quote produces Java's \Q...\E, which
        // JavaScript reads as a literal Q and E, so the filter matched nothing
        // at all and every navigation timed out.
        String childSelector = ".main-nav a:visible:text-is(\"" + child.replace("\"", "\\\"") + "\")";

        // The submenu does not always open on the first click. The header is
        // still wiring itself up, the click lands on the right element and
        // nothing happens — then the child link never appears and Playwright
        // spends its full 30 s on a selector that is perfectly correct. Seen
        // intermittently on "NAKİT YÖNETİMİ" > "Online DBS İşlemleri".
        //
        // Clicking the parent again costs seconds and fixes it. Each attempt
        // gets a short budget instead of one long one, so the failure arrives
        // sooner AND after more tries than the single 30 s wait allowed.
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MENU_ATTEMPTS; attempt++) {
            openParentMenu(parentMenuText);
            try {
                Locator childLink = page.locator(childSelector).first();
                childLink.waitFor(new Locator.WaitForOptions().setTimeout(MENU_OPEN_MS));
                childLink.click();
                return this;
            } catch (RuntimeException notOpen) {
                lastFailure = notOpen;
                System.out.println("[uptest] navigate: '" + child + "' " + attempt
                        + ". denemede acilmadi, menu tekrar aciliyor");
            }
        }
        throw new AssertionError("'" + parentMenuText + "' > '" + child + "' menusu "
                + MENU_ATTEMPTS + " denemede acilmadi", lastFailure);
    }

    /** How long one attempt waits for the submenu to appear. */
    private static final double MENU_OPEN_MS = 6000;
    private static final int MENU_ATTEMPTS = 3;

    /** Clicks the top-level menu item, failing by name when it is not there. */
    private void openParentMenu(String parentMenuText) {
        List<Locator> links = page.locator("i").all();
        Optional<Locator> element = links.stream()
                .filter(x -> x.innerText().equals(parentMenuText)).findFirst();
        if (element.isEmpty()) {
            // Previously an unexplained NoSuchElementException from Optional.get().
            throw new AssertionError("Ust menude '" + parentMenuText + "' bulunamadi");
        }
        element.get().click();
    }

    public CommonActions enterAmount(String label, String value) {
        failFastOnErrorDialog("enter amount");
        String wholePart;
        String fractionPart;
        if (value.contains(",")) {
            String[] parts = value.split(",");
            wholePart = parts[0];
            fractionPart = parts.length > 1 ? parts[1] : "00";
        } else {
            wholePart = value;
            fractionPart = "00";
        }

        Locator wholeField = page.getByLabel(label);
        wholeField.click();
        wholeField.fill("");
        wholeField.pressSequentially(wholePart);

        // Mid-step check, and the one that matters most: leaving the lira field
        // is what triggers server-side validation, so the dialog opens right
        // here. Without this, the next line spends 30 s retrying a click the
        // overlay will never allow, and the failure blames #amount-decimal
        // instead of naming the bank's actual complaint.
        failFastOnErrorDialog("enter amount (kurus alanina gecerken)");

        String wholeId = wholeField.getAttribute("id");  // e.g. "amount-integer"
        String fractionId = wholeId.replaceAll("-integer", "-decimal");
        Locator fractionField = page.locator("#" + fractionId);
        fractionField.click();
        fractionField.fill("");
        fractionField.pressSequentially(fractionPart);

        fractionField.blur();

        // blur() is exactly what triggers server-side validation here, so the
        // dialog it raises belongs to this step, not the next one.
        failFastOnErrorDialog("enter amount (sonrasi)");

        // Read BOTH boxes back. These are masked inputs and a mask that refuses
        // what was typed leaves the box empty while every call reports success
        // — the same shape as the date field, and this screen has already
        // answered a submit with "Toplam tutar giriniz" on a form that looked
        // filled. Checked after the blur so the mask has done its reformatting.
        assertAmountBox(label, wholeField, wholePart, "lira");
        assertAmountBox(label, fractionField, fractionPart, "kurus");
        return this;
    }

    /**
     * One half of an amount, compared on digits.
     *
     * Digits only, because a mask reformats as it goes: typing "1250" into the
     * lira box leaves "1.250" behind, which is the same number and a different
     * string. The empty mask strips to nothing, which is exactly the failure
     * this is here to catch.
     */
    private static void assertAmountBox(String label, Locator box, String expected, String half) {
        String actual = box.inputValue();
        if (!actual.replaceAll("\\D", "").equals(expected.replaceAll("\\D", ""))) {
            throw new AssertionError("'" + label + "' alaninin " + half + " kutusuna '" + expected
                    + "' yazilamadi; kutuda '" + actual + "' var. Maskeli bir alan, yazilani "
                    + "reddetmis olabilir — bu adim yesil gecseydi form bos tutarla gonderilir "
                    + "ve ekran 'Toplam tutar giriniz' derdi.");
        }
    }

    /**
     * Picks an option by its text.
     *
     * Two widgets wear the same label here. A plain {@code <select>} is driven
     * natively. A Select2-wrapped one is NOT: its real select is hidden, and
     * writing to it with force sets the value without the application ever
     * noticing — the step goes green, the field still reads "Seçiniz", and
     * anything the app loads in response (a dependent dropdown, a name looked
     * up from a code) never happens. So the widget is driven the way a user
     * drives it: open it, click the option.
     *
     * CLAUDE.md already recorded this for the account picker; it turns out to
     * hold for ordinary business dropdowns on the same screens.
     */
    public CommonActions select(String optionText, String label) {
        failFastOnErrorDialog("select");
        Locator container = findValueElement(label);

        if (container.locator(".select2-selection").count() > 0) {
            selectFromSelect2(optionText, label);
            return this;
        }

        Locator select = container.locator("select");
        select.selectOption(new SelectOption().setLabel(optionText),
                new Locator.SelectOptionOptions().setForce(true));

        // Read it back. setForce(true) means "write the value even if the
        // element looks undriveable", which is sometimes necessary here and is
        // also exactly how a selection goes green without the application ever
        // noticing — the Select2 branch above already learned that and checks;
        // this branch never did. Forcing is a decision about HOW to write; not
        // looking afterwards is the bug.
        assertThat(select.locator("option:checked"))
                .hasText(optionText.trim(),
                        new LocatorAssertions.HasTextOptions().setTimeout(SELECT_CONFIRM_MS));
        return this;
    }

    // Account picker rendered as a Select2 v4 combobox (rich rows:
    // text / branch / accountnumber), NOT a native <select>: the real
    // #...Idx select is aria-hidden, so the generic `select` step cannot drive
    // it. Open the widget via its field label, then click the option whose
    // digits contain the wanted account number (spacing/formatting ignored).
    public CommonActions selectAccount(String accountNumber, String label) {
        failFastOnErrorDialog("select account");
        openSelect2(label);
        String want = accountNumber.replaceAll("\\D", "");
        List<Locator> options = page.locator("li.select2-results__option").all();
        for (Locator option : options) {
            if (option.innerText().replaceAll("\\D", "").contains(want)) {
                option.click();
                // Same read-back as `select` next door, on digits rather than
                // text because that is what identifies an account. Clicking an
                // option and never asking whether it landed is how three
                // separate debugging rounds on this app started.
                assertThat(findValueElement(label).locator(".select2-selection"))
                        .containsText(spacedDigits(want),
                                new LocatorAssertions.ContainsTextOptions()
                                        .setTimeout(SELECT_CONFIRM_MS));
                return this;
            }
        }
        throw new AssertionError("No account option matching '" + accountNumber
                + "' (" + options.size() + " options listed)");
    }

    /**
     * The account's digits, allowed to be spaced out however the widget prints
     * them: "1234 5678" still matches 12345678.
     *
     * A plain string would not do. The search above deliberately strips
     * non-digits from BOTH sides before comparing, because this application
     * groups account numbers for display — so the rendered text almost never
     * equals the number as the scenario wrote it.
     *
     * Built by hand rather than with Pattern.quote: Playwright hands the
     * pattern to the BROWSER, and Java's \Q...\E means nothing to JavaScript —
     * a trap this file has already paid for once in navigateTo.
     */
    private static Pattern spacedDigits(String digits) {
        StringBuilder out = new StringBuilder();
        for (char digit : digits.toCharArray()) {
            out.append(digit).append("\\s*");
        }
        return Pattern.compile(out.toString());
    }

    // Same picker, but choose by 0-based index over the listed options.
    public CommonActions selectAccount(int index, String label) {
        failFastOnErrorDialog("select account at index");
        openSelect2(label);
        page.locator("li.select2-results__option").nth(index).click();
        return this;
    }

    /** Slow enough for the widget's keyup filter to keep up; same as masked typing. */
    private static final double TYPE_DELAY_MS = 60;

    // ============ STEPS WHOSE WORK LIVES IN THE CLASSES ABOVE ============

    /** @see RadioActions */
    public CommonActions selectRow(String rowLabel) {
        radios.selectRow(rowLabel);
        return this;
    }

    /** @see RadioActions */
    public CommonActions chooseOption(String optionLabel) {
        radios.chooseOption(optionLabel);
        return this;
    }

    /** @see RadioActions */
    public CommonActions selectRow(int index) {
        radios.selectRow(index);
        return this;
    }

    /** @see RadioActions */
    public CommonActions check(String label) {
        radios.check(label);
        return this;
    }

    /** @see Uploads */
    public CommonActions upload(String label, String fileName) {
        uploads.upload(label, fileName);
        return this;
    }

    /** @see Verifications */
    public CommonActions verifyTextIsDisplayed(String expectedText) {
        verifications.verifyTextIsDisplayed(expectedText);
        return this;
    }

    /** @see Verifications */
    public CommonActions verifyFieldHasAmount(String label, String expectedAmount) {
        verifications.verifyFieldHasAmount(label, expectedAmount);
        return this;
    }

    /** @see Verifications */
    public CommonActions verifyFieldEquals(String label, String expectedText) {
        verifications.verifyFieldEquals(label, expectedText);
        return this;
    }

    /** @see Verifications */
    public CommonActions verifyFieldIsMasked(String label) {
        verifications.verifyFieldIsMasked(label);
        return this;
    }

    /**
     * The same guard the action methods run, exposed for callers outside this
     * class. Catalog discovery uses it as the "did the screen advance, or did
     * the app refuse?" signal after clicking a Devam/Onay button: a modal means
     * the data was rejected, and scanning the covered page would produce a
     * catalog of whatever is behind the overlay.
     */
    public void assertNoErrorDialog(String action) {
        failFastOnErrorDialog(action);
    }

    // ====== THE NAMES THIS CLASS'S OWN STEPS CALL, NOW ANSWERED ELSEWHERE ======

    private void failFastOnErrorDialog(String action) {
        dialogs.failFastOnErrorDialog(action);
    }

    private Locator visibleDialog() {
        return dialogs.visibleDialog();
    }

    private String dialogMessage(Locator dialog) {
        return dialogs.dialogMessage(dialog);
    }

    private void closeDatePicker() {
        datePicker.closeDatePicker();
    }

    private boolean pickFromCalendar(String label, Locator field, String value) {
        return datePicker.pickFromCalendar(label, field, value);
    }

    private void typeInto(Locator field, String value) {
        datePicker.typeInto(field, value);
    }

    private Locator findValueElement(String label) {
        return fields.findValueElement(label);
    }

    private void selectFromSelect2(String optionText, String label) {
        dropdowns.selectFromSelect2(optionText, label);
    }

    private void openSelect2(String label) {
        dropdowns.openSelect2(label);
    }

    private void awaitButtonOrLabel(String label, Locator target) {
        dropdowns.awaitButtonOrLabel(label, target);
    }

    private boolean awaitSelect2Field(String label) {
        return dropdowns.awaitSelect2Field(label);
    }

    private boolean isSelect2Field(String label) {
        return dropdowns.isSelect2Field(label);
    }

    private Locator openSelect2Search(String label) {
        return dropdowns.openSelect2Search(label);
    }

    private AssertionError missingClickTarget(String label, Throwable cause) {
        return explain.missingClickTarget(label, cause);
    }

    private AssertionError missingField(String label, Throwable cause) {
        return explain.missingField(label, cause);
    }
}
