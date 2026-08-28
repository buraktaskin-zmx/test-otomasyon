package com.ykb.uplift.uptest.playwright.ngc.steps;

import com.ykb.uplift.uptest.playwright.common.TestContext;
import com.ykb.uplift.uptest.playwright.ngc.steps.util.NGC;
import io.cucumber.java.en.Given;

/**
 * NGC-specific step definitions only. Generic steps (navigate, click, enter,
 * select, verify, amount, wait, go to) live in CommonSteps and are shared with
 * NGI.
 *
 * NGC is built lazily because ctx.page is null until the @Before hook runs.
 */
public class NGCSteps {

    private final TestContext ctx;
    private NGC ngc;

    public NGCSteps(TestContext ctx) {
        this.ctx = ctx;
    }

    private NGC ngc() {
        if (ngc == null) ngc = new NGC(ctx.page);
        return ngc;
    }

    @Given("login with cif {string} and userid {string}")
    public void login(String cif, String userId) {
        ngc().login(cif, userId);
    }
}
