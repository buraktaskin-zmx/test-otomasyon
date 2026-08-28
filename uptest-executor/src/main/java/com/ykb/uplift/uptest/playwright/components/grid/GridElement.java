package com.ykb.uplift.uptest.playwright.components.grid;


import com.ykb.uplift.uptest.playwright.components.selectbox.account.Money;

public class GridElement {

    private final int index;

    private final String label;

    private final String value;

    public GridElement(int i, String label, String value) {
        this.index = i;
        this.label = label;
        this.value = value;
    }

    public int getIndex() {
        return index;
    }

    public String getLabel() {
        return label;
    }

    public String getValue() {
        return value;
    }

    public Money getMonetaryValue(){
        return new Money(value);
    }
}
