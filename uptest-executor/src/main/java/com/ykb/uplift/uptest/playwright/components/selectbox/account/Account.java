package com.ykb.uplift.uptest.playwright.components.selectbox.account;

import com.microsoft.playwright.Locator;

import java.util.List;

public class Account {

    private final int index;

    private final String type;

    private final String branch;

    private final String accountNumber;

    private final Money rest;

    private final Money elasticLimit;

    private final Money limit;

    public Account(int index, List<Locator> spans) {
        this.index = index;
        this.type = spans.get(0).innerText();
        this.branch = spans.get(1).innerText();
        this.accountNumber = spans.get(2).innerText() + spans.get(3).innerText();
        this.rest = new Money(spans.get(4).innerText());
        this.elasticLimit = new Money(spans.get(5).innerText());
        this.limit = new Money(spans.get(6).innerText());;
    }

    public String getType() {
        return type;
    }

    public String getBranch() {
        return branch;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public int getIndex() {
        return index;
    }
}

