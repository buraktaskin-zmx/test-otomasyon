package com.ykb.uplift.uptest.playwright.discovery;

import com.ykb.uplift.uptest.playwright.common.RunContext;
import com.ykb.uplift.uptest.playwright.common.TestContext;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NOT analyst/LLM vocabulary: excluded from VocabularyExtractor (see its
 * DISCOVERY_PACKAGE check). These steps read and probe a screen; they do not
 * describe how to test one, and a generated scenario must never contain them.
 *
 * A flow is discovered by alternating scans with the flow's own buttons, each
 * screen carrying its own values:
 *
 *   Then discover screen catalog
 *   And fill discovered fields:
 *     | Ödeme Tutarı | 1250,55 |
 *   And click "Devam"
 *   Then discover screen catalog
 *
 * The values are per screen rather than per scenario on purpose: a label that
 * the screen does not offer is then unambiguously a typo, and
 * {@link CatalogDiscovery#fillCurrentScreen} can fail on the spot instead of
 * hoping a later screen claims it.
 *
 * The draft is rewritten after every scan, so a flow that dies on screen three
 * still leaves screens one and two on disk — which is the whole point when the
 * reason it died is the thing being investigated.
 */
public class DiscoverySteps {

    private static final String OUTPUT_FILE = "catalog-draft.yaml";

    private final TestContext ctx;
    private CatalogDiscovery discovery;

    public DiscoverySteps(TestContext ctx) {
        this.ctx = ctx;
    }

    /** Built lazily: constructors run before @Before, so ctx.page is null here. */
    private CatalogDiscovery discovery() {
        if (discovery == null) {
            discovery = new CatalogDiscovery(ctx.page);
        }
        return discovery;
    }

    @Then("discover screen catalog")
    public void discoverScreenCatalog() {
        discovery().scan();
        write(discovery().toYaml());
    }

    /**
     * One screen's values: {@code | Etiket | Deger |} per row. Which verb each
     * row needs — enter / enter amount / select / select account — comes from
     * the scan that just ran, never from the analyst.
     */
    @And("fill discovered fields:")
    public void fillDiscoveredFields(DataTable table) {
        Map<String, String> values = new LinkedHashMap<>();
        for (List<String> row : table.asLists(String.class)) {
            if (row.size() >= 2) {
                values.put(row.get(0).trim(), row.get(1).trim());
            }
        }

        discovery().fillCurrentScreen(values)
                .forEach(line -> System.out.println("[uptest] fill: " + line));
    }

    private void write(String yaml) {
        Path runDir = RunContext.get();
        Path target = (runDir != null) ? runDir.resolve(OUTPUT_FILE) : Paths.get("target", OUTPUT_FILE);
        try {
            Files.writeString(target, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
        System.out.println("[uptest] catalog draft -> " + target);
    }
}
