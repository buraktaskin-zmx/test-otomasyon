package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.List;

/**
 * Radios and checkboxes: a result grid's row, a fork between two forms, and a
 * box ticked by its caption.
 *
 * Split out of {@link CommonActions} unchanged; CommonActions keeps the public
 * steps and hands them here.
 */
final class RadioActions {

    private final Page page;
    private final ErrorDialogs dialogs;

    RadioActions(Page page, ErrorDialogs dialogs) {
        this.page = page;
        this.dialogs = dialogs;
    }

    // Result grids pick a row with a radio button, not a <select>: there is no
    // fieldcontainer and no label-to-field binding, so the generic `select` step
    // cannot reach them. Match the row by the text next to its radio (the radio's
    // own cell first, then the whole row, so a value in any column still matches).
    void selectRow(String rowLabel) {
        failFastOnErrorDialog("select row");
        awaitRadios();
        String want = rowLabel.trim();
        List<Locator> radios = page.locator("input[type='radio']").all();

        for (Locator radio : radios) {
            if (rowText(radio, "td").contains(want)) {
                clickRadio(radio);
                return;
            }
        }
        StringBuilder seen = new StringBuilder();
        for (Locator radio : radios) {
            String text = rowText(radio, "tr");
            seen.append("'").append(text.replace("\n", " | ")).append("' ");
            if (text.contains(want)) {
                clickRadio(radio);
                return;
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
    void chooseOption(String optionLabel) {
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
                return;
            }
        }

        // The caption is on the page but no radio claims it — the markup puts
        // the text somewhere none of optionText's four strategies reach. Click
        // what the user clicks, then prove a radio actually took it rather than
        // reporting a success this class has no evidence for.
        if (captionArrived && clickedCaption(want)) {
            return;
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
    void selectRow(int index) {
        failFastOnErrorDialog("select row at index");
        awaitRadios();
        clickRadio(page.locator("input[type='radio']").nth(index));
        return;
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
    void check(String label) {
        failFastOnErrorDialog("check");

        Locator box = page.getByLabel(label);
        if (box.count() == 0) {
            throw new AssertionError("'" + label + "' adinda bir onay kutusu bulunamadi. "
                    + "Etiket ekranda yazdigi gibi mi?");
        }
        Locator first = box.first();

        if (first.isChecked()) {
            return;
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
        return;
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

    private void failFastOnErrorDialog(String action) {
        dialogs.failFastOnErrorDialog(action);
    }
}
