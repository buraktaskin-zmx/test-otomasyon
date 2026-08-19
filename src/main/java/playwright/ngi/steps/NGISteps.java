package com.ykb.uplift.uptest.playwright.ngi.steps;

import com.ykb.uplift.uptest.playwright.common.TestContext;
import com.ykb.uplift.uptest.playwright.ngi.steps.util.NGI;
import io.cucumber.java.en.Given;

/**
 * NGI-specific step definitions only. Generic steps (click, enter, select,
 * verify, amount, wait, go to) live in CommonSteps and are shared with NGC.
 *
 * NGI is built lazily because ctx.page is null until the @Before hook runs.
 */
public class NGISteps {

    private final TestContext ctx;
    private NGI ngi;

    public NGISteps(TestContext ctx) {
        this.ctx = ctx;
    }

    private NGI ngi() {
        if (ngi == null) ngi = new NGI(ctx.page);
        return ngi;
    }

    @Given("login to ngi with cif {string}")
    public void loginNgi(String cif) {
        ngi().loginNgi(cif);
    }
}
