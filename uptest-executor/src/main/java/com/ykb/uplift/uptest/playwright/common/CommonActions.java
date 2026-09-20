package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;

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

        target.first().click();
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
    private boolean pickFromCalendar(Locator field, String value) {
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
        if (cell.count() == 0) return false;
        cell.first().click();

        closeDatePicker();
        if (!valueTook(field, value)) {
            throw new AssertionError("Takvimden '" + value + "' secildi ama alanda '"
                    + field.inputValue() + "' yaziyor");
        }
        return true;
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
        if (isDatePicker(field) && pickFromCalendar(field, value)) {
            failFastOnErrorDialog("enter (sonrasi)");
            return this;
        }

        field.fill(value);

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
        return this;
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

        container.locator("select").selectOption(new SelectOption().setLabel(optionText),
                new Locator.SelectOptionOptions().setForce(true));
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
                return this;
            }
        }
        throw new AssertionError("No account option matching '" + accountNumber
                + "' (" + options.size() + " options listed)");
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
        page.waitForSelector("input[type='radio']");
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

    // Same picker, but choose by 0-based index over the radio buttons on the page.
    public CommonActions selectRow(int index) {
        failFastOnErrorDialog("select row at index");
        page.waitForSelector("input[type='radio']");
        clickRadio(page.locator("input[type='radio']").nth(index));
        return this;
    }

    // Text belonging to a radio: its own cell (scope "td") or its row (scope "tr").
    private String rowText(Locator radio, String scope) {
        Locator container = radio.locator("xpath=ancestor::" + scope + "[1]");
        return container.count() > 0 ? container.innerText().trim() : "";
    }

    // A plain radio can be checked directly. A custom-styled one is visually
    // hidden behind its <label> (the drawn circle IS the label), and Playwright
    // refuses to click a hidden input — so click the label, like a user does.
    private void clickRadio(Locator radio) {
        if (radio.isVisible()) {
            radio.check();
        } else {
            radio.locator("xpath=ancestor::td[1]").locator("label").first().click();
        }
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
        assertThat(valueSpan).containsText("TL",
                new LocatorAssertions.ContainsTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
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

        if (inputFields.count() > 0) {
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

        return this;
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
