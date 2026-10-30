package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * The application's modal: whether one is up, what it says, and the guard that
 * stops an action from running behind it.
 *
 * Split out of {@link CommonActions} unchanged.
 */
final class ErrorDialogs {

    private final Page page;
    private final DatePicker datePicker;

    ErrorDialogs(Page page, DatePicker datePicker) {
        this.page = page;
        this.datePicker = datePicker;
    }

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

    /**
     * The same modal, asked for in the three shapes it can take.
     *
     * Wider than {@link #DIALOG_BOX} and deliberately NOT a replacement for
     * it: that one gates the click guard, which has been right on these
     * screens for a long time and is not worth disturbing. This one only
     * decides where a VERIFY looks, where being too narrow costs the whole
     * point — the accessibility tree reports the "Fatura Bilgileri" modal as a
     * {@code dialog} node, and that node could be a div with the role, a
     * native {@code <dialog>}, or jQuery UI's wrapper. Missing it would send
     * the verify back to the page, where the first "Bayi Kodu" is the grid's
     * column header.
     */
    static final String READABLE_DIALOG = "div[role='dialog'], dialog[open], .ui-dialog";
    private static final String DIALOG_MESSAGE = "#messageTag";
    static final String DIALOG_DISMISS = "Tamam";

    private static final boolean DIALOG_GUARD_ON =
            !"false".equalsIgnoreCase(System.getenv("UPTEST_FAIL_ON_DIALOG"));
    /** Prints what the guard saw before each action; for when it does not fire. */
    private static final boolean DIALOG_DEBUG =
            "true".equalsIgnoreCase(System.getenv("UPTEST_DIALOG_DEBUG"));

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
    Locator visibleDialog() {
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

    /**
     * A visible modal, decided by the BOX alone.
     *
     * visibleDialog() above gates on the OVERLAY, and rightly so: the overlay
     * is what swallows a click, and that method exists to stop an action from
     * landing on a covered page. Reading is the other case — nothing is being
     * pressed, so whether the page behind is blocked does not matter. What
     * matters is that a dialog is on screen, because then a label the step
     * names is almost certainly the copy inside it rather than the one on the
     * list behind.
     *
     * Separate from visibleDialog() rather than a flag on it, because that
     * method also answers to UPTEST_FAIL_ON_DIALOG — the switch that turns the
     * guard off. Turning off a guard should not quietly change where a verify
     * looks.
     */
    Locator visibleDialogBox() {
        Locator box = page.locator(READABLE_DIALOG);
        int count = box.count();
        for (int i = 0; i < count; i++) {
            if (box.nth(i).isVisible()) {
                return box.nth(i);
            }
        }
        return null;
    }

    /** Whatever the modal is telling the user, as a single line. */
    String dialogMessage(Locator dialog) {
        Locator message = dialog.locator(DIALOG_MESSAGE);
        Locator source = (message.count() > 0) ? message.first() : dialog;
        return source.innerText().replaceAll("\\s+", " ").trim();
    }

    void failFastOnErrorDialog(String action) {
        // A calendar left open by the previous step — or one that opened a
        // moment after it ended — covers the fields below it. Closed before
        // every action that this guard already runs ahead of.
        closeDatePicker();
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

    private void closeDatePicker() {
        datePicker.closeDatePicker();
    }
}
