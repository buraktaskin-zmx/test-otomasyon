package orchestrator.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a .feature file tells the registry about itself.
 *
 * The whole design rests on one claim: the file is the record. Title, step
 * count, the rules it covers and the data it needs all have somewhere to live
 * in Gherkin already, so a registry file listing the same scenarios would be
 * a second source of truth — and this codebase has paid for one of those
 * before. These tests pin the claim.
 */
class ScenarioLibraryTest {

    private static final String TAGGED = """
            @R-AL-011 @needs:MAKER_01 @needs:FIRMA_LISTELI
            Feature: Alıcı Listesi İşlemleri

              Background:
                Given login with cif "MAKER_01" and userid "MAKER_01"

              Scenario: Firma seçilmeden Listele çalışmaz
                Firma alanı boş bırakılıp Listele tıklanır;
                sorgu çalışmaz.

                And click "Listele"
                Then verify "Seçim yapınız." text is displayed
            """;

    @Test
    @DisplayName("tags carry the rule and the data bindings")
    void tagsAreTheBindings() {
        ScenarioCard card = ScenarioLibrary.parse("alici/01.feature", "alici", TAGGED);

        assertThat(card.ruleIds()).containsExactly("R-AL-011");
        assertThat(card.needs()).containsExactly("MAKER_01", "FIRMA_LISTELI");
        assertThat(card.needsNone()).isFalse();
        assertThat(card.needsUndeclared()).isFalse();
        assertThat(card.broken()).isFalse();
    }

    @Test
    @DisplayName("the scenario's own name is the title, not the feature's")
    void theScenarioNamesTheTest() {
        // The Feature line names the SCREEN; the Scenario line names what was
        // tested. The table's first column is read by someone scanning for
        // the second. Same rule ScenarioName.of applies to the run history,
        // which is also what makes the last-run join work.
        ScenarioCard card = ScenarioLibrary.parse("alici/01.feature", "alici", TAGGED);

        assertThat(card.title()).isEqualTo("Firma seçilmeden Listele çalışmaz");
        assertThat(card.description()).isEqualTo(
                "Firma alanı boş bırakılıp Listele tıklanır; sorgu çalışmaz.");
    }

    @Test
    @DisplayName("Background steps are counted — they run")
    void backgroundCounts() {
        // Two scenario steps plus one Background step. Counting only the
        // scenario body would call a full login-and-navigate test a two-step
        // one, which is the number an analyst would use to judge it.
        assertThat(ScenarioLibrary.parse("alici/01.feature", "alici", TAGGED).stepCount())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an unparseable file is listed as broken, never dropped")
    void brokenFilesStayVisible() {
        // Dropping it would be worse than showing it: nobody goes looking for
        // a row that was never there, so a file with a typo would simply stop
        // being part of the suite without anyone noticing.
        ScenarioCard card = ScenarioLibrary.parse(
                "alici/02.feature", "alici", "Bu bir Gherkin dosyası değil.\n");

        assertThat(card.broken()).isTrue();
        assertThat(card.title()).isEqualTo("02.feature");
    }

    @Test
    @DisplayName("an untagged scenario is readable and visibly unbound")
    void noTagsIsLegalAndVisible() {
        ScenarioCard card = ScenarioLibrary.parse("alici/03.feature", "alici", """
                Feature: Alıcı Listesi İşlemleri

                  Scenario: Bir şey
                    Given click "Listele"
                """);

        assertThat(card.broken()).isFalse();
        assertThat(card.ruleIds()).isEmpty();
        assertThat(card.needs()).isEmpty();
        // Nobody wrote anything down -- which is NOT the same as "checked,
        // there are none". The column must not show them alike.
        assertThat(card.needsUndeclared()).isTrue();
    }

    @Test
    @DisplayName("@needs:none is a decision, an absent tag is an omission")
    void declaredNothingIsNotTheSameAsNothingDeclared() {
        ScenarioCard declared = ScenarioLibrary.parse("alici/04.feature", "alici", """
                @R-AL-010 @needs:none
                Feature: Alıcı Listesi İşlemleri

                  Scenario: Panel üç seçenek gösterir
                    Given click "Listele"
                """);

        assertThat(declared.needs()).isEmpty();
        assertThat(declared.needsNone()).isTrue();
        assertThat(declared.needsUndeclared()).isFalse();
    }

    @Test
    @DisplayName("tags that are neither rule nor need are kept, not discarded")
    void otherTagsSurvive() {
        // @mutating is how a scenario says it changes data. A registry that
        // silently drops every tag it does not understand teaches people
        // that tags do nothing.
        ScenarioCard card = ScenarioLibrary.parse("alici/05.feature", "alici", """
                @R-AL-013 @needs:LISTE_BOS @mutating @regression
                Feature: Alıcı Listesi İşlemleri

                  Scenario: Tek satır
                    Given click "Listele"
                """);

        assertThat(card.otherTags()).containsExactly("mutating", "regression");
    }

    @Test
    @DisplayName("an open question outranks a producible value")
    void blockedBeatsProducible() {
        // GECERLI_TCKN is `uretim`: the checksum algorithm is public, so the
        // value looks producible. It is still unusable while S-18 -- "does
        // the application accept a synthetic TCKN?" -- is unanswered. Reading
        // `how` alone would call this READY and send a scenario to its death.
        DataAlias producible = new DataAlias("GECERLI_TCKN", "alici_verisi", "Geçerli TCKN", "",
                "uretim", "", "S-18", "");
        // BLOCKED, not MISSING: "nobody has answered the question" and
        // "nobody has supplied the data" are requests to different people.
        assertThat(producible.readiness()).isEqualTo(DataAlias.Readiness.BLOCKED);

        DataAlias free = new DataAlias("GECERSIZ_TCKN_KISA", "alici_verisi", "Kısa TCKN", "",
                "sabit", "22", "", "");
        assertThat(free.readiness()).isEqualTo(DataAlias.Readiness.READY);

        DataAlias seeded = new DataAlias("LISTE_BOS", "listeler", "", "", "seed", "", "", "");
        assertThat(seeded.readiness()).isEqualTo(DataAlias.Readiness.SEED);

        DataAlias absent = new DataAlias("VIEWER_01", "kullanicilar", "", "", "eksik", "", "", "");
        assertThat(absent.readiness()).isEqualTo(DataAlias.Readiness.MISSING);

        // The column prints the phrase, not the key: a reader scanning fifty
        // rows wants the need, and "Geçerli TCKN" is the need.
        assertThat(producible.shown()).isEqualTo("Geçerli TCKN");
        assertThat(seeded.shown()).isEqualTo("LISTE_BOS");
    }
}
