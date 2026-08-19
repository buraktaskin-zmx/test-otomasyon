package com.ykb.uplift.uptest.playwright.components.selectbox.account;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;

public class AccountSelectBox {

    private final Page page;

    private final Locator container;

    private final Locator selectBox;

    private List<Locator> selectOptionsCache;

    private AccountSelectBox(Page page, String elementId){
        this.page = page;
        container = page.locator("#" + elementId).first();
        selectBox = container.locator(".select2-container");
    }

    public static AccountSelectBox of(Page driver, String elementId){
        return new AccountSelectBox(driver, elementId);
    }

    private boolean selectIsActive(){
        return page.isVisible(".select2-drop-active");
    }

    private void activeSelect(){
        if(!selectIsActive()){
            selectBox.click();
        }
    }

    private List<Locator> getSelectOptions(){
        if(selectOptionsCache == null){
            activeSelect();
            selectOptionsCache = page.locator(".select2-results-dept-0").all();
        }

        return selectOptionsCache;
    }

    public boolean hasAccounts(){
        return getSelectOptions().size() > 1;
    }

    public void selectAccount(int index){
        if(index >= getSelectOptions().size() || index < 0){
            throw new NoSuchAccountInSelectBoxException(index, getSelectOptions().size());
        }

        getSelectOptions().get(index+1).click();
    }

    public void selectAccount(String accountNumber){
        List<Account> accounts = this.getAccounts();
        Account account = accounts.stream()
                .filter(x-> accountNumber.equals(x.getAccountNumber())).findFirst()
                .orElseThrow(()->new NoSuchAccountInSelectBoxException(accountNumber));

        selectAccount(account.getIndex());
    }


    public List<Account> getAccounts(){
        activeSelect();
        List<Account> accounts = new ArrayList<>();
        for(int i=1;i<getSelectOptions().size();i++){
            List<Locator> spans = getSelectOptions().get(i).locator("span").all();
            if(spans.size()  == 10){
                Account account = new Account(
                        i,
                        spans
                );
                accounts.add(account);
            }
        }

        return accounts;
    }

}
