package com.ykb.uplift.uptest.playwright.ngc.steps;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class PWRecord {
    public static void main(String[] args) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            page.navigate("http://localhost:8082/ngc-web/entryNgc.nhnc.do?lang=tr");
            page.locator("#userCode").click();
            page.locator("#password").click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Giriş").setExact(true)).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("3")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("4")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("0")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("2")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("0")).click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Giriş")).click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Ödeme Oluştur / Talep Et")).click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Ödeme Talep Et")).click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("İşlem yapmak istediğiniz")).click();
            page.getByRole(AriaRole.LISTITEM).filter(new Locator.FilterOptions().setHasText("Vadesiz TL HesabıSETBASI SBTR12 0006 7010 0000 0003 4322 279.604,97 TL0,00 TL9.")).click();
            page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("TCKN / VKN")).click();
            page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("TCKN / VKN")).fill("21203030786");
            page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Ad Soyad / Unvan")).click();
            page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Ad Soyad / Unvan")).fill("test");
            page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Ödeme Tutarı")).click();
            page.getByRole(AriaRole.IMG, new Page.GetByRoleOptions().setName("...")).click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("27").setExact(true)).click();
            page.locator("#btnSubmit span").filter(new Locator.FilterOptions().setHasText("Devam")).click();
            page.getByText("/01/2026- GENEL TOPLU DATADIR KULLANMAYINIZ").click();
            assertThat(page.locator("#payeeDetail")).containsText("09/01/2026- GENEL TOPLU DATADIR KULLANMAYINIZ");
            assertThat(page.locator("#payeeDetail")).containsText("03432227");
            assertThat(page.locator("#debtorDetail")).containsText("21203030786");
            assertThat(page.locator("#debtorDetail")).containsText("test");
            assertThat(page.locator("#processDetail")).containsText("10,00 TL");
            assertThat(page.locator("#processDetail")).containsText("27/02/2026");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Onay")).click();
            assertThat(page.locator("#dataform")).containsText("Ödeme talebiniz karşı tarafa iletilmiştir. Tarafınıza yapılacak ödemenin detaylarını Banko İşlemleri'nden görüntüleyebilirsiniz.");
            assertThat(page.locator("#ykbHeaderTitle")).containsText("Ödeme Talep Et");
            assertThat(page.locator("#suggestion0")).containsText("Ödeme Oluştur / Talep Et");
            assertThat(page.locator("#suggestion1")).containsText("Banko İşlemleri");
        }
    }
}
