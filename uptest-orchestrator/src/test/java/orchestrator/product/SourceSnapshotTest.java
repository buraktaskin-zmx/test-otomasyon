package orchestrator.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a scenario's rules and aliases said when it was reviewed, against what
 * they say now.
 *
 * The gap this closes: a rule being renamed or deleted is already caught by
 * the id check, and a rule whose TEXT changed is not — the id still matches,
 * the chip still reads "bound", and every scenario on it quietly goes on
 * testing the old wording.
 */
class SourceSnapshotTest {

    private static final String PRODUCT = """
            app: ngc
            product: banko
            name: "Banko İşlemleri"
            flows: [banko-islemleri]
            """;

    private static ProductModel product(String rulesYaml) {
        return product(rulesYaml, "");
    }

    private static ProductModel product(String rulesYaml, String testDataYaml) {
        return ProductModel.parse("ngc", "banko",
                new ProductModel.Sources(PRODUCT, rulesYaml, testDataYaml, "", "", "", ""))
                .orElseThrow();
    }

    private static final String RULES = """
            rules:
              - id: R-BI-003
                title: "Hızlı Tarih Seçim seçenekleri"
                status: DOĞRULANDI
                description: >
                  Hızlı Tarih Seçim alanında aşağıdaki seçenekler bulunur.
                degerler:
                  secenekler: ["Bu Hafta", "Son 1 Ay", "Son 9 Ay", "Son 12 Ay"]
                  varsayilan: "Son 1 Ay"
                messages: []
            """;

    private static final ScenarioFields SCENARIO = new ScenarioFields(
            "Hızlı Tarih Seçim seçenekleri listelenir",
            List.of("R-BI-003"), List.of("none"), List.of(), List.of(),
            List.of("Hızlı Tarih Seçim alanına tıkla"),
            List.of("Bu Hafta, Son 1 Ay, Son 9 Ay, Son 12 Ay seçenekleri listelenir"),
            "Feature: Banko İşlemleri\n\n  Scenario: Hızlı Tarih Seçim seçenekleri listelenir\n");

    @Test
    @DisplayName("degerler is read off the rule, a scalar as a one-item list")
    void readsValueLists() {
        Rule rule = product(RULES).rules().get(0);

        assertThat(rule.values().get("secenekler"))
                .containsExactly("Bu Hafta", "Son 1 Ay", "Son 9 Ay", "Son 12 Ay");
        assertThat(rule.values().get("varsayilan")).containsExactly("Son 1 Ay");
        assertThat(product(RULES).values()).contains("Son 9 Ay", "Son 12 Ay");
    }

    @Test
    @DisplayName("nothing moved, nothing reported")
    void quietWhenUnchanged() {
        ProductModel before = product(RULES);

        assertThat(SourceSnapshot.compare(
                SourceSnapshot.capture(SCENARIO, before), SCENARIO, product(RULES))).isEmpty();
    }

    @Test
    @DisplayName("a value dropped from the rule is named, and so is the line still carrying it")
    void reportsDroppedValue() {
        List<SourceSnapshot.Source> reviewed = SourceSnapshot.capture(SCENARIO, product(RULES));
        ProductModel after = product(RULES.replace("\"Son 9 Ay\", ", ""));

        List<SourceSnapshot.SourceChange> changes =
                SourceSnapshot.compare(reviewed, SCENARIO, after);

        assertThat(changes).hasSize(1);
        SourceSnapshot.SourceChange change = changes.get(0);
        assertThat(change.id()).isEqualTo("R-BI-003");
        assertThat(change.state()).isEqualTo(SourceSnapshot.SourceChange.CHANGED);
        assertThat(change.removed()).containsExactly("Son 9 Ay");
        assertThat(change.added()).isEmpty();
        assertThat(change.fields()).extracting(SourceSnapshot.FieldChange::field)
                .containsExactly("deger:secenekler");
        // The scenario still spells it out, and that is the line to look at.
        assertThat(SCENARIO.expected().get(0)).contains("Son 9 Ay");
    }

    @Test
    @DisplayName("a reworded description is a change even when every value stayed")
    void reportsRewording() {
        List<SourceSnapshot.Source> reviewed = SourceSnapshot.capture(SCENARIO, product(RULES));
        ProductModel after = product(RULES.replace(
                "Hızlı Tarih Seçim alanında aşağıdaki seçenekler bulunur.",
                "Alanda yalnızca çoklu seçim yapılabilir."));

        List<SourceSnapshot.SourceChange> changes =
                SourceSnapshot.compare(reviewed, SCENARIO, after);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).fields()).extracting(SourceSnapshot.FieldChange::field)
                .containsExactly("description");
        assertThat(changes.get(0).removed()).isEmpty();
    }

    @Test
    @DisplayName("a rule the product no longer has is GONE, not CHANGED")
    void reportsDeletion() {
        List<SourceSnapshot.Source> reviewed = SourceSnapshot.capture(SCENARIO, product(RULES));

        List<SourceSnapshot.SourceChange> changes =
                SourceSnapshot.compare(reviewed, SCENARIO, product("rules: []"));

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).state()).isEqualTo(SourceSnapshot.SourceChange.GONE);
    }

    @Test
    @DisplayName("an unstamped scenario reports nothing — a badge on every row says nothing")
    void silentWithoutAStamp() {
        assertThat(SourceSnapshot.compare(List.of(), SCENARIO, product("rules: []"))).isEmpty();
    }

    @Test
    @DisplayName("a rule the scenario has since untagged is not a finding")
    void untaggedIsNotStale() {
        List<SourceSnapshot.Source> reviewed = SourceSnapshot.capture(SCENARIO, product(RULES));
        ScenarioFields untagged = new ScenarioFields(SCENARIO.title(), List.of(), SCENARIO.needs(),
                List.of(), List.of(), SCENARIO.steps(), SCENARIO.expected(), SCENARIO.gherkin());

        assertThat(SourceSnapshot.compare(reviewed, untagged, product("rules: []"))).isEmpty();
    }

    @Test
    @DisplayName("an alias whose definition moved is reported too")
    void aliasChangesAreTracked() {
        String data = """
                listeler:
                  LISTE_BOS:
                    kisa: "0 kayıtlı liste"
                    cozum: seed
                """;
        ScenarioFields needsAlias = new ScenarioFields(SCENARIO.title(), List.of(),
                List.of("LISTE_BOS"), List.of(), List.of(), SCENARIO.steps(),
                SCENARIO.expected(), SCENARIO.gherkin());

        List<SourceSnapshot.Source> reviewed =
                SourceSnapshot.capture(needsAlias, product("rules: []", data));
        ProductModel after = product("rules: []", data.replace("cozum: seed", "cozum: eksik"));

        List<SourceSnapshot.SourceChange> changes =
                SourceSnapshot.compare(reviewed, needsAlias, after);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).kind()).isEqualTo(SourceSnapshot.Source.ALIAS);
        assertThat(changes.get(0).id()).isEqualTo("LISTE_BOS");
        assertThat(changes.get(0).fields()).extracting(SourceSnapshot.FieldChange::after)
                .containsExactly("eksik");
    }

    @Test
    @DisplayName("re-stamping against the new text clears the finding")
    void reviewingClearsIt() {
        ProductModel after = product(RULES.replace("\"Son 9 Ay\", ", ""));

        // What the "Gözden geçirildi" button does: stamp what it says now.
        List<SourceSnapshot.Source> restamped = SourceSnapshot.capture(SCENARIO, after);

        assertThat(SourceSnapshot.compare(restamped, SCENARIO, after)).isEmpty();
    }
}
