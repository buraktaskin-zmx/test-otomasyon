package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jQuery UI's calendar, driven and closed the way a person uses it, and the
 * masked-box typing that is the fallback when no calendar opens.
 *
 * Split out of {@link CommonActions} unchanged: every method here is the one
 * that lived there, moved as it was.
 */
final class DatePicker {

    private final Page page;

    DatePicker(Page page) {
        this.page = page;
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
    static boolean isDatePicker(Locator field) {
        String css = field.getAttribute("class");
        return css != null && css.contains("hasDatepicker");
    }

    /**
     * Picks a date through the calendar widget: year, then month, then the day
     * cell. Returns false — rather than throwing — when this is not a date we
     * can drive, so the caller can still try typing; a wrong guess here must
     * not cost the step.
     */
    boolean pickFromCalendar(String label, Locator field, String value) {
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
    static boolean valueTook(Locator field, String value) {
        return strip(field.inputValue()).equals(strip(value));
    }

    private static String strip(String text) {
        return text.replaceAll("[^0-9\\p{L}]", "");
    }

    /**
     * Types the value with real key events, the way a user does, so masks and
     * other keystroke-driven widgets see what they are listening for.
     */
    void typeInto(Locator field, String value) {
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
     * Only while the calendar is actually up: an unconditional Escape would
     * also dismiss the error dialog that negative scenarios need to read and
     * close with "Tamam".
     *
     * And CHECKED, in three tries. A single Escape was the whole of it, and it
     * left the calendar open over "Fatura Tipi" on DBS Fatura Oluştur: the
     * select spent its timeout clicking into the calendar, and the moment a
     * person closed the calendar by hand the very same select went through.
     * Escape only works while focus is in the date box — after a day was
     * clicked it sits in the calendar — so the calendar's own close button
     * ("Tamam", what a person presses) goes first, then a mousedown outside
     * (jQuery UI closes on one; dispatched, so nothing is actually clicked),
     * then Escape.
     */
    void closeDatePicker() {
        Locator picker = page.locator(DATE_PICKER);
        if (!isUp(picker)) {
            return;
        }
        // Closing is not enough; it has to STAY closed. The calendar was seen
        // closed and then open again over "Fatura Tipi", its date box focused
        // with the text selected: focus went back to the box and the box opens
        // its calendar on focus. So each round closes it, takes the focus off
        // the date box, lets a re-open happen, and only then looks again.
        for (int round = 1; round <= PICKER_CLOSE_ROUNDS; round++) {
            String how = dismiss(picker);
            page.waitForTimeout(PICKER_SETTLE_MS);
            if (!isUp(picker)) {
                if (round > 1) {
                    System.out.println("[uptest] takvim: " + round + ". turda kapali kaldi (" + how + ")");
                }
                return;
            }
            System.out.println("[uptest] takvim: kapatildi (" + how + ") ama yeniden acildi — tur " + round);
        }
        throw new AssertionError("Takvim kapatildikca yeniden aciliyor; altindaki alanlara tiklanamaz."
                + " (jQuery hide, Escape, Tamam ve dis tiklama denendi; odak tarih kutusundan alindi.)");
    }

    /**
     * One attempt to close the calendar; says which way did it.
     *
     * Order is the whole of it, and the first version had it backwards. The
     * widget is jQuery UI's, and it reopens the moment its input regains
     * focus — which is what jQuery itself does after a day is clicked, so a
     * close followed by a blur closed it and then let it come straight back.
     *
     * So: ask jQUERY to hide it and blur the input it belongs to, in one go,
     * because that is the pair that has to happen together. Only when jQuery
     * is not reachable do the imitations follow, and they are ordered by what
     * they need: Escape while focus is still in the box, then the widget's
     * own "Tamam", then a mousedown outside (dispatched, so nothing is really
     * clicked) — and the blur comes LAST there, once the thing is shut.
     */
    private String dismiss(Locator picker) {
        if (hideThroughJQuery()) {
            if (closed(picker)) {
                return "jQuery";
            }
        }
        page.keyboard().press("Escape");
        if (closed(picker)) {
            blurActiveElement();
            return "Escape";
        }
        Locator done = picker.locator("button.ui-datepicker-close");
        if (done.count() > 0 && done.first().isVisible()) {
            done.first().click();
            if (closed(picker)) {
                blurActiveElement();
                return "Tamam";
            }
        }
        page.locator("body").dispatchEvent("mousedown");
        blurActiveElement();
        return closed(picker) ? "dis tiklama" : "hicbiri";
    }

    /**
     * Closes the calendar through the widget's own API and takes the focus off
     * the input it belongs to.
     *
     * The same reasoning as driving a Select2 through its rendered control
     * rather than its hidden select: this is the widget, so this is how it is
     * told to close. Returns false when jQuery is not reachable under either
     * name, and the imitations above take over.
     */
    private boolean hideThroughJQuery() {
        try {
            Object hidden = page.evaluate("""
                    () => {
                      const jq = window.jQuery || window.$;
                      if (!jq || !jq.datepicker || typeof jq.datepicker._hideDatepicker !== 'function') {
                        return false;
                      }
                      const input = jq.datepicker._lastInput;
                      jq.datepicker._hideDatepicker();
                      if (input && typeof input.blur === 'function') { input.blur(); }
                      return true;
                    }""");
            return Boolean.TRUE.equals(hidden);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Takes focus off whatever has it, so a focus handler cannot re-open the calendar. */
    private void blurActiveElement() {
        try {
            page.evaluate("() => { const e = document.activeElement;"
                    + " if (e && e !== document.body && typeof e.blur === 'function') e.blur(); }");
        } catch (RuntimeException navigating) {
            // Nothing focused to speak of on a page in flight.
        }
    }

    private static final int PICKER_CLOSE_ROUNDS = 3;
    private static final double PICKER_SETTLE_MS = 400;

    private static boolean isUp(Locator picker) {
        return picker.count() > 0 && picker.first().isVisible();
    }

    /** Waits a moment for the calendar's close animation; true once it is gone. */
    private static boolean closed(Locator picker) {
        try {
            picker.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN).setTimeout(PICKER_CLOSE_MS));
            return true;
        } catch (RuntimeException stillOpen) {
            return false;
        }
    }

    private static final double PICKER_CLOSE_MS = 1500;
}
