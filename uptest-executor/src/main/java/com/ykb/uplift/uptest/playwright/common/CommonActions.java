package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

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

    /**
     * The application's modal is a jQuery UI dialog. Taken from a real failure
     * log rather than guessed:
     *
     *   <div role="dialog" aria-modal="true" class="ui-dialog ... ui-front ui-draggable">
     *     <div id="messageTag">Girdiğiniz bilgilere göre uygun müşteri ...</div>
     *   </div>
     *   <div class="ui-widget-overlay ui-front"></div>
     *
     * The overlay is what Playwright reports as "intercepts pointer events", so
     * it — not the title text — is the reliable signal that a modal is up.
     * jQuery UI leaves both nodes in the DOM and merely hides them, so every
     * check must be on visibility, never on presence.
     */
    private static final String DIALOG_OVERLAY = ".ui-widget-overlay";
    private static final String DIALOG_BOX = "div[role='dialog']";
    private static final String DIALOG_MESSAGE = "#messageTag";
    private static final String DIALOG_DISMISS = "Tamam";

    private static final boolean DIALOG_GUARD_ON =
            !"false".equalsIgnoreCase(System.getenv("UPTEST_FAIL_ON_DIALOG"));
    /** Prints what the guard saw before each action; for when it does not fire. */
    private static final boolean DIALOG_DEBUG =
            "true".equalsIgnoreCase(System.getenv("UPTEST_DIALOG_DEBUG"));

    public CommonActions(Page page) {
        this.page = page;
    }

    // ======================= GENERIC ACTIONS ===============================


    public CommonActions click(String label) {
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
        Locator target = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label))
                .or(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(label)));

        if (target.count() > 1) {
            System.out.println("[uptest] click: '" + label + "' icin " + target.count()
                    + " aday var, ilki tiklaniyor -> "
                    + String.join(" | ", target.allInnerTexts()));
        }

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
        return this;
    }

    /**
     * jQuery UI's calendar, which this app opens whenever a date input takes
     * focus — including the focus {@code fill()} itself gives it.
     */
    private static final String DATE_PICKER = "#ui-datepicker-div";

    /** dd/MM/yyyy, with any of the separators these screens use. */
    private static final Pattern DATE_VALUE = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");

    /** How long the calendar gets to appear after the field is clicked. */
    private static final double PICKER_MS = 5000;

    /** jQuery UI stamps this class on every input it has attached a calendar to. */
    private static boolean isDatePicker(Locator field) {
        String css = field.getAttribute("class");
        return css != null && css.contains("hasDatepicker");
    }

    /**
     * Picks a date through the calendar widget: year, then month, then the day
     * cell. Returns false — rather than throwing — when this is not a date we
     * can drive, so the caller can still try typing; a wrong guess here must
     * not cost the step.
     */
    private boolean pickFromCalendar(String label, Locator field, String value) {
        Matcher parts = DATE_VALUE.matcher(value.trim());
        if (!parts.matches()) return false;

        field.click();
        Locator picker = page.locator(DATE_PICKER);
        try {
            picker.waitFor(new Locator.WaitForOptions().setTimeout(PICKER_MS));
        } catch (RuntimeException noCalendar) {
            return false;
        }

        picker.locator("select.ui-datepicker-year").selectOption(parts.group(3));
        // jQuery UI numbers its months from zero.
        picker.locator("select.ui-datepicker-month")
                .selectOption(String.valueOf(Integer.parseInt(parts.group(2)) - 1));

        // Leading zeros are not printed in the grid, and the unselectable
        // leading/trailing days of neighbouring months must not match.
        String day = String.valueOf(Integer.parseInt(parts.group(1)));
        Locator cell = picker.locator("td[data-handler='selectDay'] a:text-is(\"" + day + "\")");
        if (cell.count() == 0) {
            cell = picker.locator("td:not(.ui-datepicker-unselectable) a:text-is(\"" + day + "\")");
        }
        if (cell.count() == 0) {
            // THROW, do not fall through. The two returns above mean "this is
            // not a calendar I can drive" and falling back to typing is right
            // for them. This one is different in kind: the calendar OPENED and
            // does not offer that day, which is the application telling us the
            // date is not allowed.
            //
            // Treating it as the other two is how a real run went green on a
            // date that was never entered. "Başlangıç Vadesi" may not be today
            // — the bank requires a future maturity — so the grid greys today
            // out; we fell through, typed the digits with real keystrokes, the
            // mask accepted them, valueTook() agreed, and the step reported
            // success for a date the screen had just refused.
            throw new AssertionError(datePickerRefused(label, value, picker, day));
        }
        cell.first().click();

        closeDatePicker();
        if (!valueTook(field, value)) {
            throw new AssertionError("Takvimden '" + value + "' secildi ama alanda '"
                    + field.inputValue() + "' yaziyor");
        }
        return true;
    }

    /**
     * Why the calendar would not take this day, in a form that separates the
     * two possible causes.
     *
     * Without the list of days the grid DID offer, the two are
     * indistinguishable and they call for opposite fixes:
     *
     *   the grid offers 10..31 and we asked for 9  -> the APPLICATION refuses
     *       the date (a maturity date may not be today). The scenario is
     *       asking for a day this field does not accept, and an analyst fixes
     *       it by writing a different offset.
     *   the grid offers 1..31 and we still matched nothing -> OUR locator is
     *       wrong, and no scenario change will help.
     *
     * The selectable days are read from the same two locators the picker tried,
     * so what is printed is exactly the set that was searched.
     */
    private String datePickerRefused(String label, String value, Locator picker, String day) {
        List<String> selectable = picker.locator("td[data-handler='selectDay'] a").allInnerTexts();
        if (selectable.isEmpty()) {
            selectable = picker.locator("td:not(.ui-datepicker-unselectable) a").allInnerTexts();
        }
        return "'" + label + "' alaninda '" + value + "' tarihi SECILEMEDI: takvim acildi ama "
                + day + " gunu tiklanabilir degil."
                + (selectable.isEmpty()
                        ? " Takvimde hic secilebilir gun yok — ay/yil dogru acilmamis olabilir."
                        : " Takvimin sundugu gunler: " + String.join(", ", selectable) + ".")
                + " | Bu gunler istediginizi icermiyorsa tarihi UYGULAMA reddediyor (orn. vade "
                + "bugunden ileri olmak zorunda) — senaryoda farkli bir tarih yazin, orn. "
                + "enter date \"" + label + "\" \"+1 gun\". Iceriyorsa sorun bizim takvim "
                + "kodumuzda.";
    }

    /**
     * Did the field actually end up holding what we asked for? Separators are
     * ignored on both sides: a mask supplies its own slashes, and the empty
     * mask ("__/__/____") strips to nothing, which is exactly the failure this
     * is here to catch.
     */
    private static boolean valueTook(Locator field, String value) {
        return strip(field.inputValue()).equals(strip(value));
    }

    private static String strip(String text) {
        return text.replaceAll("[^0-9\\p{L}]", "");
    }

    /**
     * Types the value with real key events, the way a user does, so masks and
     * other keystroke-driven widgets see what they are listening for.
     */
    private void typeInto(Locator field, String value) {
        field.click();
        field.press("Control+a");
        field.press("Delete");
        field.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(TYPE_DELAY_MS));
    }

    /** Slow enough for a mask's keypress handler to keep up, fast enough not to hurt. */
    private static final double TYPE_DELAY_MS = 60;

    /**
     * Closes the calendar a date field left hanging over the form.
     *
     * Nothing dismisses it on its own, and it covers whatever sits below.
     * fill() does not care — it sets a value without pointing at anything — so
     * the next few text fields still worked and the damage surfaced later, on
     * the first step that really clicks: "Toplam Tutar" spent 30 s being told
     * that an <a data-date="29"> was intercepting its pointer events.
     *
     * Escape rather than a click elsewhere, and only while the calendar is
     * actually up: an unconditional Escape would also dismiss the error dialog
     * that negative scenarios need to read and close with "Tamam".
     */
    private void closeDatePicker() {
        Locator picker = page.locator(DATE_PICKER);
        if (picker.count() > 0 && picker.first().isVisible()) {
            page.keyboard().press("Escape");
        }
    }

    public CommonActions navigateToUrl(String url) {
        Page.NavigateOptions options = new Page.NavigateOptions();
        options.setTimeout(1000);
        try {
            page.navigate(url, options);
        } catch (Exception e) {

        }
        return this;
    }

    public CommonActions enter(String label, String value) {
        failFastOnErrorDialog("enter");
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

    /**
     * How long the widget gets to show the value that was just chosen. Doubles
     * as the pause the application needs to react to the choice.
     */
    private static final double SELECT_CONFIRM_MS = 10000;

    /** Opens the widget, clicks the matching option, and checks it took. */
    private void selectFromSelect2(String optionText, String label) {
        openSelect2(label);
        String want = optionText.trim();

        List<Locator> options = page.locator("li.select2-results__option").all();
        Locator chosen = null;
        for (Locator option : options) {
            if (option.innerText().trim().equals(want)) {
                chosen = option;
                break;
            }
        }
        if (chosen == null) {
            // A contained match before giving up: an option often prints more
            // than the thing being chosen ("93002 - FORD BAYİ").
            for (Locator option : options) {
                if (option.innerText().contains(want)) {
                    chosen = option;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new AssertionError("'" + label + "' icin '" + optionText
                    + "' secenegi yok. Listelenen: " + String.join(" | ", page
                    .locator("li.select2-results__option").allInnerTexts()));
        }

        chosen.click();

        // The click is not the point — the value landing is. A selection that
        // silently fails to stick has cost three separate debugging rounds on
        // this app: the step went green and the damage surfaced fields later,
        // on whatever the app was supposed to load in response. Auto-waiting
        // here also gives that reaction time to happen before the next field.
        assertThat(findValueElement(label).locator(".select2-selection"))
                .containsText(want,
                        new LocatorAssertions.ContainsTextOptions().setTimeout(SELECT_CONFIRM_MS));
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

    // Opens the Select2 v4 widget belonging to a field, by its label. Shared by
    // the account picker and by ordinary dropdowns: both are the same widget,
    // they differ only in how the caller then matches an option (digits for an
    // account, text for a dropdown). The list renders at <body> level, so
    // callers read the options page-wide, not inside the field container.
    private void openSelect2(String label) {
        Locator container = findValueElement(label);
        container.locator(".select2-selection").click();
        page.waitForSelector("li.select2-results__option");
    }

    // Result grids pick a row with a radio button, not a <select>: there is no
    // fieldcontainer and no label-to-field binding, so the generic `select` step
    // cannot reach them. Match the row by the text next to its radio (the radio's
    // own cell first, then the whole row, so a value in any column still matches).
    public CommonActions selectRow(String rowLabel) {
        failFastOnErrorDialog("select row");
        awaitRadios();
        String want = rowLabel.trim();
        List<Locator> radios = page.locator("input[type='radio']").all();

        for (Locator radio : radios) {
            if (rowText(radio, "td").contains(want)) {
                clickRadio(radio);
                return this;
            }
        }
        StringBuilder seen = new StringBuilder();
        for (Locator radio : radios) {
            String text = rowText(radio, "tr");
            seen.append("'").append(text.replace("\n", " | ")).append("' ");
            if (text.contains(want)) {
                clickRadio(radio);
                return this;
            }
        }
        throw new AssertionError("No selectable row matching '" + rowLabel
                + "' (" + radios.size() + " rows listed): " + seen);
    }

    /**
     * Picks a radio option by the text beside it, anywhere on the page.
     *
     * {@code selectRow} already drives radios but only inside a RESULT GRID: it
     * matches on {@code ancestor::td} then {@code ancestor::tr}, so a radio in a
     * plain form section matches nothing. That is the gap this fills — on DBS
     * Fatura Oluştur the form is gated behind "Oluşturma Tipi" (Bilgi Girişi /
     * Dosya Yükleme) and nothing in the vocabulary could reach it.
     *
     * The first version of this used {@code getByRole(RADIO)} and failed on the
     * real screen with "0 options on the page", intermittently enough to look
     * like a race. It was two bugs, and both are the ones this file already has
     * lessons about:
     *
     *   - NO WAIT. {@code Locator.count()} does not auto-wait; it answers about
     *     the DOM as it stands this instant. Called while the screen was still
     *     arriving it said zero and the step threw immediately. {@code selectRow}
     *     has always called {@link #awaitRadios} first, and so must this.
     *   - THE ACCESSIBILITY TREE DOES NOT CONTAIN A HIDDEN INPUT. These radios
     *     are custom-styled: the drawn circle is the label and the input itself
     *     is visually hidden, which is exactly why {@code clickRadio} clicks the
     *     label. A hidden element is excluded from the AX tree, so
     *     {@code getByRole} could never have seen them however long it waited.
     *     Matching the raw {@code input[type='radio']} is what works, and is
     *     what the grid picker next door has been doing all along.
     */
    public CommonActions chooseOption(String optionLabel) {
        failFastOnErrorDialog("choose");
        String want = optionLabel.trim();

        // Wait for THE OPTION, not for any radio. awaitRadios() only promises
        // that SOME radio is attached somewhere, and on this flow the button
        // before this step REPLACES the page: the first radio to arrive need
        // not be one of the fork's, and one whose label has not rendered yet
        // reads back as ''. A real run reported exactly that — "1 secenek
        // bulundu: ''" on a screen that has two — and it looked like a locator
        // bug rather than a page that had not finished arriving.
        //
        // Same shape as the selectRow lesson, one step further on: there the
        // wait was on the wrong STATE (visible, of a hidden element); here it
        // was on the wrong ELEMENT. The thing to wait for is the thing the step
        // is about.
        //
        // The caption is the right thing to wait on because the caption is what
        // a user sees: this application draws its own radios, so the input is
        // hidden and the visible text IS the control.
        boolean captionArrived = awaitOptionText(want);

        // Re-read AFTER the wait, never before: a list taken while the screen
        // was still arriving is a list of what used to be there.
        List<Locator> radios = page.locator("input[type='radio']").all();
        StringBuilder seen = new StringBuilder();
        for (Locator radio : radios) {
            String text = optionText(radio);
            seen.append("'").append(text.replace("\n", " ")).append("' ");
            if (text.contains(want)) {
                clickOption(radio, text);
                return this;
            }
        }

        // The caption is on the page but no radio claims it — the markup puts
        // the text somewhere none of optionText's four strategies reach. Click
        // what the user clicks, then prove a radio actually took it rather than
        // reporting a success this class has no evidence for.
        if (captionArrived && clickedCaption(want)) {
            return this;
        }

        throw new AssertionError("'" + optionLabel + "' secilemedi. "
                + (captionArrived
                        ? "Metin ekranda VAR ama hicbir radyo dugmesine baglanamadi: "
                        : "Metin ekranda hic BULUNAMADI (" + CHOICE_MS / 1000
                                + " sn beklendi) — ekran henuz gelmemis ya da baska bir ekrandayiz. ")
                + radios.size() + " radyo bulundu: " + seen
                + "| Bu adim IZGARA SATIRI icin degil; satir seciyorsaniz "
                + "`select row` kullanin.");
    }

    /** How long the fork's caption gets to appear after the previous click. */
    private static final double CHOICE_MS = 10000;

    /**
     * Waits for the option's own text to become visible, and says whether it
     * did.
     *
     * Returns a boolean rather than throwing because the answer is the single
     * most useful thing the failure message can carry: text present means the
     * screen is right and the locator is wrong, text absent means we are early
     * or somewhere else entirely. Those call for opposite fixes and used to be
     * indistinguishable.
     */
    private boolean awaitOptionText(String want) {
        try {
            page.getByText(want, new Page.GetByTextOptions().setExact(false)).first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(CHOICE_MS));
            return true;
        } catch (RuntimeException neverAppeared) {
            return false;
        }
    }

    /**
     * Clicks the caption itself, and checks a radio ended up selected.
     *
     * Verified rather than assumed, for the reason this file keeps relearning:
     * a step that reports success while the page ignored it costs more than a
     * failure. If nothing became checked, this reports false and the caller
     * raises the proper error.
     */
    private boolean clickedCaption(String want) {
        try {
            page.getByText(want, new Page.GetByTextOptions().setExact(false)).first().click();
        } catch (RuntimeException notClickable) {
            return false;
        }
        for (Locator radio : page.locator("input[type='radio']").all()) {
            if (radio.isChecked()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The caption a radio belongs to, outside a table.
     *
     * Four places to look because the markup is not ours to choose, in order of
     * how directly each one binds: the input's own accessible label, a label
     * wrapped around it, a label pointing at it by id, then the parent element's
     * text as a last resort. The first non-empty answer wins — a wider scope
     * would start swallowing a neighbouring option's caption.
     */
    private String optionText(Locator radio) {
        String aria = radio.getAttribute("aria-label");
        if (aria != null && !aria.isBlank()) {
            return aria.trim();
        }
        String wrapped = textOf(radio.locator("xpath=ancestor::label[1]"));
        if (!wrapped.isEmpty()) {
            return wrapped;
        }
        String id = radio.getAttribute("id");
        if (id != null && !id.isBlank()) {
            String forLabel = textOf(page.locator("label[for='" + id + "']"));
            if (!forLabel.isEmpty()) {
                return forLabel;
            }
        }
        return textOf(radio.locator("xpath=parent::*"));
    }

    private String textOf(Locator locator) {
        try {
            return locator.count() == 0 ? "" : locator.first().innerText().trim();
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * Same two-step dance as {@link #clickRadio}, minus its table assumption.
     *
     * A visible input is checked directly. A hidden one is clicked through the
     * text that stands in for it, which is the element a person actually
     * presses.
     */
    private void clickOption(Locator radio, String caption) {
        if (radio.isVisible()) {
            radio.check();
            assertRadioTook(radio, caption);
            return;
        }
        Locator wrapped = radio.locator("xpath=ancestor::label[1]");
        if (wrapped.count() > 0) {
            wrapped.first().click();
            assertRadioTook(radio, caption);
            return;
        }
        String id = radio.getAttribute("id");
        if (id != null && !id.isBlank() && page.locator("label[for='" + id + "']").count() > 0) {
            page.locator("label[for='" + id + "']").first().click();
            assertRadioTook(radio, caption);
            return;
        }
        page.getByText(caption, new Page.GetByTextOptions().setExact(true)).first().click();
        assertRadioTook(radio, caption);
    }

    /**
     * Did the radio actually end up selected?
     *
     * Four different ways of pressing it and, until now, not one of them asked.
     * {@link #check} has always asked — "clicking the label" is a stand-in for
     * clicking the input, and a stand-in can miss: the label can belong to a
     * neighbouring option, or be covered, or the application can refuse the
     * choice outright. A `choose` that goes green on an unselected radio sends
     * the run on to a form that never opened.
     */
    private static void assertRadioTook(Locator radio, String caption) {
        if (!radio.isChecked()) {
            throw new AssertionError("'" + caption + "' secenegine tiklandi ama radyo dugmesi "
                    + "secili degil. Tiklama baska bir elemana gitmis ya da uygulama secimi "
                    + "kabul etmemis olabilir.");
        }
    }

    // Same picker, but choose by 0-based index over the radio buttons on the page.
    public CommonActions selectRow(int index) {
        failFastOnErrorDialog("select row at index");
        awaitRadios();
        clickRadio(page.locator("input[type='radio']").nth(index));
        return this;
    }

    /**
     * Waits for the page's radios to exist — ATTACHED, not visible.
     *
     * The default state is "visible", and it applies to the FIRST match. On
     * Banko Alacak İşlemleri that first match is a radio inside the collapsed
     * "Detaylı Arama" panel (name=selectedSearchType), which never becomes
     * visible: the step spent its whole 30 s waiting on an element it had no
     * interest in and never looked at the grid at all. Same shape as the
     * navigateTo lesson — the locator was right, the first match was hidden.
     *
     * Deliberately NOT narrowed to ":visible": on this app a custom-styled
     * radio is visually hidden behind its label, and clickRadio already
     * handles that by clicking the label. Requiring visibility here would
     * break exactly those screens.
     */
    private void awaitRadios() {
        page.waitForSelector("input[type='radio']",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
    }

    // Text belonging to a radio: its own cell (scope "td") or its row (scope "tr").
    private String rowText(Locator radio, String scope) {
        Locator container = radio.locator("xpath=ancestor::" + scope + "[1]");
        return container.count() > 0 ? container.innerText().trim() : "";
    }

    // A plain radio can be checked directly. A custom-styled one is visually
    // hidden behind its <label> (the drawn circle IS the label), and Playwright
    // refuses to click a hidden input — so click the label, like a user does.
    /**
     * Ticks a checkbox, by the caption printed next to it.
     *
     * Same shape as {@link #clickRadio}, and for the same reason: this
     * application draws its own boxes, so the input is visually hidden and
     * Playwright refuses to click a hidden element. When it cannot be clicked,
     * its label can — that is the thing a user actually presses.
     *
     * Idempotent. Clicking an already-ticked box would UNtick it, and a step
     * that quietly does the opposite of its name is the worst kind. Asking
     * first costs nothing.
     *
     * Verified afterwards rather than assumed. Every expensive bug in this
     * project has been a step that reported success while the page ignored it
     * — the masked date, the Select2 write, the collapsed text compare — so
     * this one ends by reading the state back and failing loudly if the box
     * did not take.
     */
    public CommonActions check(String label) {
        failFastOnErrorDialog("check");

        Locator box = page.getByLabel(label);
        if (box.count() == 0) {
            throw new AssertionError("'" + label + "' adinda bir onay kutusu bulunamadi. "
                    + "Etiket ekranda yazdigi gibi mi?");
        }
        Locator first = box.first();

        if (first.isChecked()) {
            return this;
        }

        if (first.isVisible()) {
            first.check();
        } else {
            page.getByText(label, new Page.GetByTextOptions().setExact(true)).first().click();
        }

        if (!first.isChecked()) {
            throw new AssertionError("'" + label + "' isaretlenemedi: tiklama gecti ama kutu "
                    + "hala isaretli degil.");
        }

        // These forms validate on change, and a box that reveals a new field
        // does it here — so the dialog this step provoked is reported against
        // this step rather than against whatever comes next.
        failFastOnErrorDialog("check (sonrasi)");
        return this;
    }

    private void clickRadio(Locator radio) {
        if (radio.isVisible()) {
            radio.check();
        } else {
            radio.locator("xpath=ancestor::td[1]").locator("label").first().click();
        }
        // A grid row picked but not selected sends the rest of the scenario to
        // the wrong record — every later step passes and the run documents a
        // transaction nobody asked for. Worth one read.
        assertRadioTook(radio, "izgara satiri");
    }

    /**
     * How long a verification waits for what it is looking for.
     *
     * Playwright's default is 5 s, which is shorter than this bank's submit: a
     * scenario ending in {@code click "Onay"} then
     * {@code verify "Tamam" text is displayed} failed while the result screen
     * was still on its way, with nothing actually wrong. A verification is
     * often the first step to run after a submit, so all three wait this long.
     *
     * Still an auto-wait, not a sleep: it returns the moment the assertion
     * holds, and only a real failure spends the whole budget.
     */
    private static final double VERIFY_TIMEOUT_MS = 15000;

    /** Where a bare file name is looked up. */
    private static final String ENV_TESTDATA = "UPTEST_TESTDATA_DIR";

    /** How long the file name is given to appear, on the chooser path. */
    private static final double UPLOAD_CONFIRM_MS = 10000;

    /**
     * Attaches a file to an upload control.
     *
     * The gap this closes: DBS Fatura Oluştur forks into "Bilgi Girişi" and
     * "Dosya Yükleme", {@code choose} can take the second branch, and then
     * nothing could drive the screen it lands on. The whole branch was
     * untestable.
     *
     * TWO PATHS, because these forms use both shapes and only one of them is
     * the textbook case:
     *
     *   - a real {@code <input type="file">} reachable from the label. Driven
     *     with setInputFiles, which deliberately does NOT need the input to be
     *     visible — and that matters here, because an application that draws
     *     its own "Gözat" button hides the real input behind it. A step that
     *     insisted on visibility would fail on exactly the forms this exists
     *     for.
     *   - no such input: the visible control opens the OS file dialog, which
     *     Playwright can only intercept. waitForFileChooser arms the
     *     interception BEFORE the click, because the event fires during it.
     *
     * Worth knowing where this leaves recording-based approaches: codegen
     * writes setInputFiles for the first shape and cannot record the second at
     * all — the file-chooser handling is written by hand there too. The branch
     * that is hardest for a human to script is also the one a recorder cannot
     * capture, which is the argument for a step rather than a cached trace.
     */
    public CommonActions upload(String label, String fileName) {
        failFastOnErrorDialog("upload");
        Path file = testDataFile(label, fileName);

        Locator input = fileInputFor(label);
        if (input != null) {
            input.setInputFiles(file);
        } else {
            // Armed first, then the click: the chooser opens DURING the click
            // and an interception set up afterwards has already missed it.
            page.waitForFileChooser(() -> clickUploadControl(label)).setFiles(file);
            input = fileInputFor(label);
        }

        assertFileTook(label, input, file);
        failFastOnErrorDialog("upload (sonrasi)");
        return this;
    }

    /**
     * The file to attach, as an absolute path that exists.
     *
     * A bare name is resolved under {@code UPTEST_TESTDATA_DIR} (default
     * {@code ./testdata}) so a scenario an analyst wrote stays free of machine
     * paths — the same reason a step takes a label instead of a selector. An
     * absolute path is honoured as given, for the case where the fixture lives
     * somewhere else.
     *
     * Checked HERE rather than left to Playwright. A missing file reaches the
     * browser as a failure thirty seconds later with the file name buried in a
     * driver message; failing now names the path we actually looked at, which
     * is the only thing that tells a missing fixture apart from a wrong
     * directory.
     */
    private static Path testDataFile(String label, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new AssertionError("'" + label + "' icin dosya adi verilmedi.");
        }
        String configured = System.getenv(ENV_TESTDATA);
        Path root = Paths.get(configured == null || configured.isBlank() ? "./testdata" : configured);

        Path given = Paths.get(fileName.trim());
        Path file = (given.isAbsolute() ? given : root.resolve(given)).toAbsolutePath().normalize();

        if (!Files.isRegularFile(file)) {
            throw new AssertionError("'" + label + "' icin yuklenecek dosya yok: " + file
                    + " — senaryoda yalnizca dosya ADI yazilir ve " + ENV_TESTDATA
                    + " (varsayilan ./testdata) altinda aranir. Dosyayi oraya koyun ya da "
                    + "senaryoda tam yol yazin.");
        }
        return file;
    }

    /**
     * The file input this label owns, or null when the form hides it behind a
     * control of its own.
     *
     * Same ladder as findValueElement and for the same reason: getByLabel only
     * works when the markup associates them, and on these screens it often
     * does not.
     */
    private Locator fileInputFor(String label) {
        Locator byLabel = page.getByLabel(label).locator("xpath=self::input[@type='file']");
        if (byLabel.count() > 0) {
            return byLabel.first();
        }
        Locator inContainer = labelContainer(label).locator("input[type='file']");
        if (inContainer.count() > 0) {
            return inContainer.first();
        }
        // Last resort, and only when it cannot be ambiguous: a form with
        // exactly one file input on the whole page has no other candidate.
        Locator onPage = page.locator("input[type='file']");
        return onPage.count() == 1 ? onPage.first() : null;
    }

    /** The fieldcontainer a label sits in, for scoping a search to its field. */
    private Locator labelContainer(String label) {
        return page.getByText(label, new Page.GetByTextOptions().setExact(true)).first()
                .locator("xpath=ancestor::div[contains(@class,'fieldcontainer')][1]");
    }

    /** Presses whatever opens the file dialog for this label. */
    private void clickUploadControl(String label) {
        Locator container = labelContainer(label);
        Locator button = container.getByRole(AriaRole.BUTTON).or(container.getByRole(AriaRole.LINK));
        if (button.count() > 0) {
            button.first().click();
            return;
        }
        page.getByText(label, new Page.GetByTextOptions().setExact(true)).first().click();
    }

    /**
     * Reads the attachment back, because an upload that silently attached
     * nothing is the worst shape this codebase knows: the form submits, the
     * application rejects an empty file, and the step that caused it was green.
     *
     * A file input's value is {@code C:\fakepath\<name>} — the browser hides
     * the directory, never the name, so the name is what gets compared. When
     * there is no input to read (the control is entirely custom), the file name
     * on screen is the only evidence available, and if neither exists the step
     * says it could not confirm rather than passing.
     */
    private void assertFileTook(String label, Locator input, Path file) {
        String wanted = file.getFileName().toString();

        if (input != null) {
            try {
                String value = input.inputValue();
                if (value != null && value.endsWith(wanted)) {
                    return;
                }
                throw new AssertionError("'" + label + "' alanina '" + wanted
                        + "' eklenmedi: kutu '" + value + "' diyor.");
            } catch (com.microsoft.playwright.PlaywrightException unreadable) {
                // Fall through to the on-screen check below.
            }
        }

        try {
            assertThat(page.getByText(wanted).first())
                    .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(UPLOAD_CONFIRM_MS));
        } catch (AssertionError notShown) {
            throw new AssertionError("'" + label + "' icin '" + wanted + "' secildi ama dogrulanamadi:"
                    + " okunabilir bir dosya girdisi yok ve dosya adi ekranda da gorunmuyor."
                    + " Dosyanin gercekten eklendigi KANITLANAMADI.", notShown);
        }
    }

    public CommonActions verifyTextIsDisplayed(String expectedText) {
        Locator textLocator = page.getByText(expectedText, new Page.GetByTextOptions().setExact(false)).first();
        assertThat(textLocator).isVisible(
                new LocatorAssertions.IsVisibleOptions().setTimeout(VERIFY_TIMEOUT_MS));
        return this;
    }

    // Amount fields: normalize then compare numerically
    public CommonActions verifyFieldHasAmount(String label, String expectedAmount) {
        Locator container = findValueElement(label);
        // Confirm screen: read-only span filled by JS. Auto-wait until non-empty.
        Locator valueSpan = valueHolder(container);
        // TL is appended last, so this waits until the value is fully rendered.
        try {
            assertThat(valueSpan).containsText("TL",
                    new LocatorAssertions.ContainsTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
        } catch (AssertionError miss) {
            throw explainVerify(label, container, miss);
        }
        // Same shared-container case as verifyFieldEquals: the text can arrive
        // as "Toplam Tutar200,00 TL". Harmless today because the label has no
        // digits, but a label that did would corrupt the comparison silently.
        String actual = withoutLabel(label, valueSpan.innerText());

        assertAmountEquals(expectedAmount, actual);

        return this;
    }

    // Text / date fields: EXACT match, NO normalization
    public CommonActions verifyFieldEquals(String label, String expectedText) {
        Locator container = findValueElement(label);
        Locator inputFields = container.locator("input");
        // A Select2 dropdown's container holds its SEARCH box -- v3's
        // "select2-input", always empty -- so the input branch below read ""
        // on a field showing "Seçiniz". The value a person reads is the chosen
        // text: ".select2-chosen" on v3, ".select2-selection__rendered" on v4.
        Locator select2Shown = container.locator(".select2-chosen, .select2-selection__rendered");

        try {
            if (select2Shown.count() > 0) {
                assertThat(select2Shown.first()).hasText(expectedText.trim(),
                        new LocatorAssertions.HasTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
            } else if (inputFields.count() > 0) {
                // Entry screen: input field. Auto-wait until non-empty, then read.
                assertThat(inputFields.first()).hasValue(expectedText.trim(),
                        new LocatorAssertions.HasValueOptions().setTimeout(VERIFY_TIMEOUT_MS));
            } else {
                // Confirm screen: read-only value. Three shapes seen on this app —
                // a value span, a plain table cell, and (DBS Fatura Oluştur "Onay")
                // one container holding the label AND the value, which reads back
                // as "Bayi Kodu93002". Rather than guessing which element is the
                // value, accept the text with the label optionally in front of it.
                assertThat(valueHolder(container))
                        .hasText(labelThenValue(label, expectedText.trim()),
                                new LocatorAssertions.HasTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
            }
        } catch (AssertionError miss) {
            throw explainVerify(label, container, miss);
        }

        return this;
    }

    /** Any non-whitespace: "the value has arrived", whatever it turns out to be. */
    private static final Pattern ANY_TEXT = Pattern.compile("\\S");

    /**
     * What a masked value looks like on these screens: a run of stars inside
     * the value, as in "20****694".
     *
     * Two or more, so that a stray asterisk — a required-field marker, a
     * footnote — cannot make an unmasked account number pass. Written as a
     * \\u escape for the bullet because this file is hand-copied between
     * machines and a copy through the wrong encoding would silently change
     * what the pattern matches.
     */
    private static final Pattern MASKED = Pattern.compile("[*\\u2022]{2,}");

    /**
     * A field whose value the application deliberately HIDES — "20****694".
     *
     * The existing verifies all compare against a value the scenario supplies,
     * and that is the wrong question here twice over. On a read-only screen the
     * scenario never typed the value, so it cannot know it; and the account
     * number differs per record, so writing it down would tie the test to one
     * row. What the analyst actually wants to assert is not a value but a
     * PROPERTY: that the bank is still hiding the middle of the number.
     *
     * Without this step the model had no way to say that. Asked to "verify the
     * recipient account is masked" it reached for the nearest value-shaped verb
     * and invented {@code has amount "*"}; the validator rightly refused the
     * invented value, and the repair turn then deleted the assertion and called
     * the scenario valid — a test that walks to the screen and checks nothing.
     *
     * Takes no expected value on purpose. That is what makes it earnable on a
     * screen where nothing is ever filled in.
     */
    public CommonActions verifyFieldIsMasked(String label) {
        Locator container = findValueElement(label);
        Locator holder = valueHolder(container);

        try {
            // Auto-wait rather than read-and-hope: a detail screen paints its
            // table after the request settles, and reading too early finds an
            // empty cell — which is indistinguishable from an unmasked blank.
            assertThat(holder).containsText(ANY_TEXT,
                    new LocatorAssertions.ContainsTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
        } catch (AssertionError nothingArrived) {
            throw explainVerify(label, container, nothingArrived);
        }

        String shown = withoutLabel(label, holder.innerText()).trim();
        if (MASKED.matcher(shown).find()) {
            return this;
        }
        throw notMasked(label, shown);
    }

    /**
     * Two failures that read alike and need opposite fixes.
     *
     * "The value is not masked" is the application's answer and a real finding.
     * "We read the label back" is OUR locator giving up on a screen whose label
     * and value share one span — the open issue on the DBS Firma Fatura summary,
     * and the shape this Banko detail screen is in (the agent reported its rows
     * as "Alıcı Hesabı 20****694", one run of text). Reporting the second as the
     * first would send someone to the bank to ask why masking had been turned
     * off.
     */
    private AssertionError notMasked(String label, String shown) {
        if (shown.isEmpty() || shown.equalsIgnoreCase(label.trim())) {
            return new AssertionError("'" + label + "' degeri okunamadi: ekrandan '" + shown
                    + "' geldi — yani etiketin kendisi, degeri degil. Bu ekranda etiket ve deger"
                    + " ayni span'i paylasiyor ve findValueElement ikisini ayiramiyor. Alan"
                    + " maskeli OLSA DA bu adim bunu goremez; once deger cozumlemesi duzeltilmeli.");
        }
        return new AssertionError("'" + label + "' maskeli degil: ekranda '" + shown
                + "' yaziyor. Maskeli bir deger en az iki ardisik yildiz tasir (ornek: 20****694).");
    }

    // ========================= HELPERS ===================================

    /**
     * The element inside a value container that actually carries the text. The
     * app's own forms wrap it in a value span; a plain confirmation table has
     * no span at all and the cell holds the text directly.
     */
    /**
     * The expected value, allowing the field's own label to be glued in front
     * of it — which is how the DBS confirmation screen renders: label and value
     * share one container, so the text reads "Bayi Kodu93002".
     *
     * Still an exact match on the value itself (the two-day normalize bug is
     * not being reopened): the label is the ONLY thing tolerated, and only at
     * the start.
     *
     * Escaped by hand rather than with Pattern.quote, because Playwright hands
     * the pattern to the BROWSER — Java's \Q...\E means nothing to JavaScript
     * and would match no text at all.
     */
    private static Pattern labelThenValue(String label, String value) {
        return Pattern.compile("^\\s*(?:" + escapeRegex(label.trim()) + "\\s*)?"
                + escapeRegex(value) + "\\s*$");
    }

    private static String escapeRegex(String text) {
        return text.replaceAll("([\\\\^$.|?*+()\\[\\]{}/])", "\\\\$1");
    }

    /** Drops a label glued to the front of a value, when it is there. */
    private static String withoutLabel(String label, String text) {
        String trimmed = text.trim();
        String prefix = label.trim();
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()).trim() : trimmed;
    }

    private static Locator valueHolder(Locator container) {
        Locator span = container.locator("span.elem-span-infos");
        return span.count() > 0 ? span.first() : container.first();
    }

    // Returns the input (if entry field) or the value span (if read-only display)
    // that belongs EXACTLY to this label. Auto-waits handled by the callers.
    private Locator findValueElement(String label) {
        Locator labelEl = page.getByText(label, new Page.GetByTextOptions().setExact(true)).first();
        Locator container = labelEl.locator("xpath=ancestor::div[contains(@class,'fieldcontainer')][1]");

        // Plain confirmation table (DBS Fatura Oluştur "Onay" step): there is no
        // fieldcontainer anywhere above the label — it is an ordinary two-cell
        // row, label left, value right. Without this the chain below resolved to
        // nothing and every verify on that screen reported "element(s) not
        // found" instead of a value mismatch.
        //
        // Falls back to `container` — NOT to the label element — when there is
        // no such cell either. Returning the label was tried and reverted: on
        // the entry screen it turned a clear "element not found" into a 30 s
        // selectOption timeout hunting for a <select> inside a <span>.
        if (container.count() == 0) {
            Locator nextCell = labelEl.locator("xpath=ancestor::td[1]/following-sibling::td[1]");
            if (nextCell.count() > 0) {
                return nextCell.first();
            }
        }

        // modularFormTable layout: label and value are in SEPARATE <td> fieldcontainers.
        // The label cell's fieldcontainer holds only the label span, so look at the value
        // cell in the next <td>. Banko keeps using the single-container path above.
        Locator siblingValueCell = container
                .locator("xpath=ancestor::td[1]/following-sibling::td[1]//div[contains(@class,'fieldcontainer')]");
        if (siblingValueCell.count() > 0) {
            return siblingValueCell.first();
        }
        return container;
    }

    // Works for Turkish format (e.g 120,55 1.250,55)
    private String normalizeAmount(String raw) {
        if (raw == null) return "";
        // Strip everything except digits, dots and commas (removes TL, spaces, letters...)
        String cleaned = raw.replaceAll("[^0-9.,]", "");
        // Remove thousands' separator dots
        cleaned = cleaned.replace(".", "");
        // Convert the decimal comma to a dot
        cleaned = cleaned.replace(",", ".");
        if (cleaned.isEmpty()) return "";
        try {
            // Parse to a number so that 250 and 250.00 are treated as equal
            double value = Double.parseDouble(cleaned);
            return String.valueOf(value);
        } catch (NumberFormatException ex) {
            // Not a number: fall back to the cleaned raw string
            return cleaned;
        }
    }

    private void assertAmountEquals(String expected, String actual) {
        String e = normalizeAmount(expected);
        String a = normalizeAmount(actual);
        if (!e.equals(a)) {
            throw new AssertionError(
                    "Expected value did not match the screen. Expected: '" + expected
                            + "' (normalized: " + e + "), On screen: '" + actual + "' (normalized: " + a + ")");
        }
    }

    // ================== WHAT THE SCREEN ACTUALLY OFFERED ==================

    /**
     * Built on first use, because only a FAILING step ever asks: enumerating
     * the screen costs a snapshot and a container walk, and a passing run must
     * not pay for it.
     */
    private ScreenLabels screen;

    private ScreenLabels screen() {
        if (screen == null) {
            screen = new ScreenLabels(page);
        }
        return screen;
    }

    /**
     * "'X' bulunamadi" plus the nearest things that ARE on the screen.
     *
     * Playwright's own answer to a locator that matches nothing is 30 s of
     * waiting and then "element(s) not found", which names neither the cause
     * nor the fix. In practice the cause is almost always one of three, and
     * the candidate list separates them at a glance: the label is misspelled
     * (a near match appears), the catalog has gone stale (a renamed field
     * appears), or the previous step never opened the screen (nothing
     * appears).
     *
     * Note this does NOT shorten the wait - the timeout still elapses first.
     * It only makes the sentence at the end of it worth reading.
     */
    private AssertionError missingClickTarget(String label, Throwable cause) {
        return new AssertionError("'" + label + "' adinda tiklanabilir bir buton veya link yok."
                + ScreenLabels.report(label, screen().clickableNames(), "buton/link")
                + " | Playwright: " + firstLine(cause), cause);
    }

    private AssertionError missingField(String label, Throwable cause) {
        return new AssertionError("'" + label + "' adinda bir alan yok."
                + ScreenLabels.report(label, screen().fieldLabels(), "alan")
                + " | Playwright: " + firstLine(cause), cause);
    }

    /**
     * Tells the three verify failures apart. They read almost alike today and
     * call for opposite fixes:
     *
     *   value wrong      -> the application is misbehaving, or the expectation is
     *   label absent     -> the scenario or the catalog is wrong
     *   label present but
     *   unresolvable     -> the SCREEN's structure is not one of the three
     *                       shapes findValueElement knows
     *
     * The third is not hypothetical: it is the open issue on the DBS Firma
     * Fatura summary, where label and value share a span class. Naming it
     * stops that failure from being read as a value mismatch.
     */
    private AssertionError explainVerify(String label, Locator container, AssertionError miss) {
        if (container.count() > 0) {
            return miss;
        }
        Locator labelOnScreen = page.getByText(label, new Page.GetByTextOptions().setExact(true));
        if (labelOnScreen.count() > 0) {
            return new AssertionError("'" + label + "' etiketi ekranda var, ama degerini tutan alan"
                    + " cozulemedi: ne fieldcontainer, ne yan hucre, ne modularFormTable."
                    + " Bu ekranin yapisi findValueElement'in bildigi uc sekilden hicbiri degil."
                    + " | Orijinal hata: " + firstLine(miss), miss);
        }
        return missingField(label, miss);
    }

    /**
     * Playwright failures run to dozens of lines of call log. The first line
     * is the fact; the rest would bury the candidate list we just added.
     */
    private static String firstLine(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(message).trim();
    }

    // ===================== ERROR DIALOG GUARD ============================

    /**
     * The application answers a rejected form with a modal ("Uyarı ... Tamam"),
     * and Playwright does NOT stop for it: the fields behind the modal stay in
     * the DOM and reachable, so fill() keeps succeeding. A scenario then walks
     * on with data the bank refused, and the run fails somewhere unrelated —
     * or, worse, passes.
     *
     * So every data-entry action asks first whether a dialog is up, and fails
     * with the dialog's own text. The failure then names the real cause and
     * lands on the first step that could not meaningfully proceed.
     *
     * Deliberately NOT applied to click() or the verify steps: a negative
     * scenario must be able to assert the error text and press "Tamam".
     *
     *   Then verify "uygun müşteri bulunmamaktadır" text is displayed
     *   And click "Tamam"
     *
     * Set UPTEST_FAIL_ON_DIALOG=false to switch the guard off entirely.
     */
    /**
     * The open modal, or {@code null} when none is up.
     *
     * The overlay decides, because the overlay is what actually blocks every
     * click — Playwright names it by class in the failure log. The dialog box
     * itself is then returned so callers can scope a search to it.
     */
    private Locator visibleDialog() {
        if (!DIALOG_GUARD_ON) {
            return null;
        }
        Locator overlay = page.locator(DIALOG_OVERLAY);
        boolean open = overlay.count() > 0 && overlay.first().isVisible();

        Locator box = page.locator(DIALOG_BOX);
        if (DIALOG_DEBUG) {
            System.out.println("[uptest] dialog: overlay count=" + overlay.count()
                    + " visible=" + open + ", box count=" + box.count());
        }
        if (!open) {
            return null;
        }
        for (int i = 0; i < box.count(); i++) {
            if (box.nth(i).isVisible()) {
                return box.nth(i);
            }
        }
        return null;
    }

    /** Whatever the modal is telling the user, as a single line. */
    private String dialogMessage(Locator dialog) {
        Locator message = dialog.locator(DIALOG_MESSAGE);
        Locator source = (message.count() > 0) ? message.first() : dialog;
        return source.innerText().replaceAll("\\s+", " ").trim();
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

    private void failFastOnErrorDialog(String action) {
        Locator dialog = visibleDialog();
        if (dialog == null) {
            return;
        }
        throw new AssertionError(
                "Uygulama bir hata diyalogu gosterdi, '" + action + "' adimi calistirilmadi: "
                        + dialogMessage(dialog)
                        + " | Beklenen bir hataysa once dogrulayin: "
                        + "verify \"<mesaj>\" text is displayed, sonra click \"" + DIALOG_DISMISS + "\".");
    }
}
