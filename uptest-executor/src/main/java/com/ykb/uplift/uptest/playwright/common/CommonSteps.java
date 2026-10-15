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

    /**
     * A date field, written as what it MEANS rather than as one particular day.
     *
     * {@code enter} can set a date too, and still does when a scenario carries a
     * literal gg/aa/yyyy. What it cannot do is survive: a generated scenario
     * freezes the date the generator computed, so one drafted today reads
     * "08/10/2026" forever and is in the past a fortnight later — refused by a
     * form whose maturity date may not be behind today. Nothing about the
     * scenario changed; it simply rotted.
     *
     * So the resolution moved to where the browser is. "+3 gün" is three days
     * from the RUN, every run. The value is handed to the same
     * {@link CommonActions#enter} as before, so the calendar-versus-typing
     * machinery below it is untouched.
     */
    @And("enter date {string} {string}")
    public void enterDate(String label, String when) {
        actions().enter(label, RelativeDate.resolve(when));
    }

    @And("enter amount {string} {string}")
    public void enterAmount(String label, String value) { actions().enterAmount(label, value); }

    @And("select {string} from {string}")
    public void select(String optionText, String label) { actions().select(optionText, label); }

    @And("select account {string} from {string}")
    public void selectAccount(String accountNumber, String label) { actions().selectAccount(accountNumber, label); }

    @And("select account at index {int} from {string}")
    public void selectAccountAtIndex(int index, String label) { actions().selectAccount(index, label); }

    @And("check {string}")
    public void check(String label) { actions().check(label); }

    @And("select row {string}")
    public void selectRow(String rowLabel) { actions().selectRow(rowLabel); }

    @And("select row at index {int}")
    public void selectRowAtIndex(int index) { actions().selectRow(index); }

    /**
     * A radio option OUTSIDE a result grid — the fork a screen puts in front of
     * its form.
     *
     * Not `select row`: that one matches on the radio's table cell and row, so a
     * radio in a plain form section matches nothing. Not `select ... from`
     * either, which drives a dropdown widget. On DBS Fatura Oluştur the form is
     * gated behind "Oluşturma Tipi" — Bilgi Girişi / Dosya Yükleme — and nothing
     * in the vocabulary could reach it.
     *
     * One argument, not two. This is a step on the way TO a form rather than a
     * field of one, and a screen has one such gate; naming the group as well
     * would be ceremony. A screen with two radio groups needs a two-argument
     * sibling, and should get one then rather than now.
     */
    @And("choose {string}")
    public void choose(String optionLabel) { actions().chooseOption(optionLabel); }

    @Then("verify the {string} field equals {string}")
    public void verifyFieldEquals(String label, String expectedValue) { actions().verifyFieldEquals(label, expectedValue); }

    /**
     * The mirror of {@link #enterDate}, and the reason it had to exist.
     *
     * `verify the {} field equals {}` compares text exactly, by design — it must
     * not normalise, because normalising once collapsed every expected value to
     * "" and made two days of assertions pass against nothing. So a scenario
     * that typed "+3 gün" and then verified "+3 gün" would look at a screen
     * showing 11/10/2026 and fail, every time, on a field that was set
     * correctly. The write and the read were speaking different languages.
     *
     * Same resolution, same moment: the offset becomes a date here too, then the
     * ordinary exact comparison runs on it.
     */
    @Then("verify the {string} has date {string}")
    public void verifyFieldHasDate(String label, String when) {
        actions().verifyFieldEquals(label, RelativeDate.resolve(when));
    }

    @Then("verify the {string} has amount {string}")
    public void verifyFieldHasAmount(String label, String expectedAmount) { actions().verifyFieldHasAmount(label, expectedAmount); }

    @Then("verify {string} text is displayed")
    public void verifyTextIsDisplayed(String expectedText) { actions().verifyTextIsDisplayed(expectedText); }

    @And("wait {string} seconds")
    public void waitStep(String seconds) {
        ctx.page.waitForTimeout(Integer.parseInt(seconds) * 1000);
    }
}
