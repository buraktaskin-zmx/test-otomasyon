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

    public NGC(Page page) {
        this.page = page;
    }

    // ======================= STEP IMPLEMENTATIONS ==============================

    private static final String LOGIN_URL = "https://ifctest.yapikredi.com.tr/ngc/indexNgc.do";
    private static final int LOGIN_PAGE_ATTEMPTS = 3;

    /** How long the page itself gets. Corporate SSL inspection makes it slow, not instant. */
    private static final double NAVIGATE_MS = 20_000;

    /**
     * How long the login box gets ONCE THE PAGE HAS LOADED. Short on purpose:
     * navigation returning means the document is there, so the box is rendered
     * or it never will be. The old 45 s were spent almost entirely on Edge's
     * error page, where nothing was ever going to appear.
     */
    private static final double LOGIN_BOX_MS = 5_000;

    /**
     * The login page, asked for again when the request itself fails.
     *
     * The test environment answers the first request of a fresh browser with
     * ERR_CONNECTION_RESET often enough to matter, and a refresh by hand
     * always fixed it. The navigation helper swallows every error by design —
     * it navigates with a 1 s timeout and ignores what comes back — so the
     * step then stood on Edge's error page waiting 45 s for a login box.
     *
     * A reset is reported AT ONCE, so there is nothing to wait for: navigate
     * here with a real timeout, let it throw, and ask again immediately. The
     * common recovery now costs about a second rather than three quarters of
     * a minute.
     */
    private void openLoginPage() {
        Throwable last = null;
        for (int attempt = 1; attempt <= LOGIN_PAGE_ATTEMPTS; attempt++) {
            try {
                page.navigate(LOGIN_URL, new Page.NavigateOptions().setTimeout(NAVIGATE_MS));
            } catch (RuntimeException refused) {
                // ERR_CONNECTION_RESET and friends: the server said no, now.
                last = refused;
                System.out.println("[uptest] login: giris sayfasi acilmadi (" + attempt + "/"
                        + LOGIN_PAGE_ATTEMPTS + ") — " + oneLine(refused.getMessage())
                        + " | hemen yeniden isteniyor");
                continue;
            }
            try {
                assertThat(page.locator("#userCode")).isVisible(
                        new LocatorAssertions.IsVisibleOptions().setTimeout(LOGIN_BOX_MS));
                return;
            } catch (AssertionError noBox) {
                // The document loaded but carries no login box: an error page
                // served with 200, or a redirect somewhere else. Worth one
                // more request, and the address says which it was.
                last = noBox;
                System.out.println("[uptest] login: sayfa yuklendi ama giris kutusu yok (" + attempt
                        + "/" + LOGIN_PAGE_ATTEMPTS + "), adres: " + page.url());
            }
        }
        throw new AssertionError("Giris sayfasi " + LOGIN_PAGE_ATTEMPTS
                + " denemede acilmadi; son adres: " + page.url(), last);
    }

    private static String oneLine(String message) {
        if (message == null) {
            return "(mesaj yok)";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
    }

    public NGC login(String cif, String userId) {

        openLoginPage();

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
