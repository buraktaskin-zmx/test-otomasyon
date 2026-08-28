package com.ykb.uplift.uptest.playwright.components.end;

import com.microsoft.playwright.Page;

public class EndPage {

    private Page page;

    private EndPage(Page page) {
        this.page = page;
    }

    public static EndPage of(Page page) {
        return new EndPage(page);
    }

    public String getSuccessMessage() {
        return page.locator(".success-wrapper-hold").innerText();
    }
}
