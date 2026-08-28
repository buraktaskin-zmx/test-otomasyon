package com.ykb.uplift.uptest.playwright.ngc.steps.util;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.ykb.uplift.uptest.playwright.common.CommonActions;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * NGC (corporate) application-specific page logic. Only the login flow differs
 * from NGI: CIF + userid. Everything generic (click, enter, navigate, select,
 * verify, amount) lives in {@link CommonActions}.
 */
public class NGC {

    private final Page page;
    private final CommonActions common;

    public NGC(Page page) {
        this.page = page;
        this.common = new CommonActions(page);
    }

    // ======================= STEP IMPLEMENTATIONS ==============================

    public NGC login(String cif, String userId) {

        common.navigateToUrl("https://ifctest.yapikredi.com.tr/ngc/indexNgc.do");

        assertThat(page.locator("#userCode")).isVisible(
                new LocatorAssertions.IsVisibleOptions()
                        .setTimeout(45 * 1000)
        );

        Locator customerCode = page.locator("#userCode");
        Locator userCode = page.locator("#userId");
        Locator password = page.locator("#password");
        Locator button = page.locator("#btnSubmit");

        customerCode.pressSequentially(cif);
        userCode.pressSequentially(userId);
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
