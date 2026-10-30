package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * What a failing step says: the label it could not find, plus the nearest
 * things that ARE on the screen.
 *
 * Split out of {@link CommonActions} unchanged.
 */
final class FailureExplanations {

    private final Page page;

    FailureExplanations(Page page) {
        this.page = page;
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
    AssertionError missingClickTarget(String label, Throwable cause) {
        return new AssertionError("'" + label + "' adinda tiklanabilir bir buton veya link yok."
                + ScreenLabels.report(label, screen().clickableNames(), "buton/link")
                + " | Playwright: " + firstLine(cause), cause);
    }

    AssertionError missingField(String label, Throwable cause) {
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
    AssertionError explainVerify(String label, Locator container, AssertionError miss) {
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
}
