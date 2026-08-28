package com.ykb.uplift.uptest.playwright.common;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;

/**
  The CommonActions page object is built lazily: ctx.page is null until the
  @Before hook runs (constructors run first), so we cannot build it in the
  constructor.
 */
public class CommonSteps {

    private final TestContext ctx;
    private CommonActions actions;

    public CommonSteps(TestContext ctx) {
        this.ctx = ctx;
    }

    private CommonActions actions() {
        if (actions == null) actions = new CommonActions(ctx.page);
        return actions;
    }

    @And("go to {string}")
    public void navigateToUrl(String url) { actions().navigateToUrl(url); }

    @And("navigate to {string} {string}")
    public void navigate(String mainMenu, String subMenu) { actions().navigateTo(mainMenu, subMenu); }

    @And("click {string}")
    public void click(String label) { actions().click(label); }

    @And("enter {string} {string}")
    public void enter(String label, String value) { actions().enter(label, value); }

    @And("enter amount {string} {string}")
    public void enterAmount(String label, String value) { actions().enterAmount(label, value); }

    @And("select {string} from {string}")
    public void select(String optionText, String label) { actions().select(optionText, label); }

    @And("select account {string} from {string}")
    public void selectAccount(String accountNumber, String label) { actions().selectAccount(accountNumber, label); }

    @And("select account at index {int} from {string}")
    public void selectAccountAtIndex(int index, String label) { actions().selectAccount(index, label); }

    @And("select row {string}")
    public void selectRow(String rowLabel) { actions().selectRow(rowLabel); }

    @And("select row at index {int}")
    public void selectRowAtIndex(int index) { actions().selectRow(index); }

    @Then("verify the {string} field equals {string}")
    public void verifyFieldEquals(String label, String expectedValue) { actions().verifyFieldEquals(label, expectedValue); }

    @Then("verify the {string} has amount {string}")
    public void verifyFieldHasAmount(String label, String expectedAmount) { actions().verifyFieldHasAmount(label, expectedAmount); }

    @Then("verify {string} text is displayed")
    public void verifyTextIsDisplayed(String expectedText) { actions().verifyTextIsDisplayed(expectedText); }

    @And("wait {string} seconds")
    public void waitStep(String seconds) {
        ctx.page.waitForTimeout(Integer.parseInt(seconds) * 1000);
    }
}
