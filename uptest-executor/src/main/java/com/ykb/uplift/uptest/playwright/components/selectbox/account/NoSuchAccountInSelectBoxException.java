package com.ykb.uplift.uptest.playwright.components.selectbox.account;

public class NoSuchAccountInSelectBoxException extends RuntimeException {

    private  int index;

    private  int listSize;

    private String accountNumber;

    public NoSuchAccountInSelectBoxException(int index, int listSize) {
        this.index = index;
        this.listSize = listSize;
    }

    public NoSuchAccountInSelectBoxException(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getListSize() {
        return listSize;
    }

    public void setListSize(int listSize) {
        this.listSize = listSize;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
}
