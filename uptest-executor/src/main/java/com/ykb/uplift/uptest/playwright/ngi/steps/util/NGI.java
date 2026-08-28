package com.ykb.uplift.uptest.playwright.ngi.steps.util;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.ykb.uplift.uptest.playwright.common.CommonActions;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * NGI (individual) application-specific page logic. Only the login flow differs
 * from NGC: customer code + Sorgula/Login + password (no CIF + userid).
 * Everything generic (click, enter, select, verify, amount) lives in
 * {@link CommonActions}.
 */
public class NGI {

    private final Page page;
    private final CommonActions common;

    public NGI(Page page) {
        this.page = page;
        this.common = new CommonActions(page);
    }

    // ======================= STEP IMPLEMENTATIONS ==============================

    public NGI loginNgi(String cif) {

        common.navigateToUrl("https://intest.yapikredi.com.tr/ngi/passResetEnter.do");

        assertThat(page.locator("#cifNoLabel")).isVisible(
                new LocatorAssertions.IsVisibleOptions()
                        .setTimeout(45 * 1000)
        );

        Locator customerCode = page.locator("#cifNoLabel");
        customerCode.fill(cif);

        common.click("Sorgula");
        common.click("Login");

        Locator password = page.locator("#password");
        Locator button = page.locator("#btnSubmit");
        password.pressSequentially("123456");
        button.click();

        try {
            Locator otpText = page.locator("#otpPassword");
            otpText.fill("34020");

            Locator otpSubmitButton = page.locator("#btnSubmit");
            otpSubmitButton.click();
        } catch (Exception e) {
        }

        return this;
    }
}
