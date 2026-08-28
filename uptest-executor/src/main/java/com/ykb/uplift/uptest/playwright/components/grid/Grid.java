package com.ykb.uplift.uptest.playwright.components.grid;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;

public class Grid {

    private final Page driver;

    private final Locator grid;

    private Grid(Page driver, String elementId){
        this.driver = driver;
        grid = driver.locator("#" + elementId);;
    }

    public static Grid of(Page driver, String elementId){
        return new Grid(driver, elementId);
    }

    public String getTitle(){
        return grid.locator("th").first().innerText();
    }

    public List<GridElement> getGridElements(){
        List<Locator> elements = grid.locator("tr").all();
        List<GridElement> gridElements = new ArrayList<>();
        for(int i=1; i<elements.size(); i++){
            Locator col = elements.get(i).locator("td").first();
            String label = col.locator("label").innerText();
            String value = col.locator("span").innerText();
            gridElements.add(new GridElement(i, label, value));
        }
        return gridElements;
    }
}
