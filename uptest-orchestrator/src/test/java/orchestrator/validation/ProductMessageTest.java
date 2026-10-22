package orchestrator.validation;

import orchestrator.catalog.CatalogModel;
import orchestrator.product.ProductModel;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scenario may assert a message the PRODUCT says the application prints.
 *
 * Until the product layer existed, {@code checkMessageIsCatalogued} accepted
 * exactly one sentence — the catalog's {@code success_message} — and its own
 * javadoc admitted what that cost: "a negative scenario asserting an error
 * dialog's text is flagged too". For a payments product that is most of the
 * test suite. Every rule worth writing about a salary file is a refusal, and a
 * screen's catalog has nowhere to record one, because a catalog is DISCOVERED
 * from a screen and a refusal is not a thing a screen displays until it
 * refuses.
 *
 * The fix widens what counts as catalogued rather than loosening the check.
 * Both halves matter, so both are pinned here: the product's messages are
 * accepted, and an invented one is still refused.
 */
class ProductMessageTest {

    /**
     * A real negative scenario: fill something, submit, assert the refusal.
     * Note the shape the executor requires — the error dialog is asserted
     * BEFORE anything else touches the page, because every data-entry step
     * fails fast while a dialog is up.
     */
    private static final String REJECTS_BAD_IBAN = """
            Feature: Maaş Ödemeleri

              Scenario: Hatalı IBAN içeren satır reddedilir
                Given login with cif "DI00002018" and userid "1335"
                And navigate to "NAKİT YÖNETİMİ" "Maaş Ödemeleri"
                And enter "Açıklama" "Ocak maaşları"
                And click "Devam"
                Then verify "Geçersiz IBAN numarası girdiniz." text is displayed
            """;

    private final ScenarioValidator validator = new ScenarioValidator();

    @Test
    @DisplayName("without a product the error message is refused, exactly as before")
    void unchangedWhenNoProductIsChosen() {
        ValidationResult result = validator.validate(
                REJECTS_BAD_IBAN, vocabulary(), screen(), null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.kind()).isEqualTo(ValidationError.Kind.PARAPHRASED_MESSAGE));
    }

    @Test
    @DisplayName("with the product that declares it, the same scenario is valid")
    void theProductMakesTheRefusalAssertable() {
        ValidationResult result = validator.validate(
                REJECTS_BAD_IBAN, vocabulary(), screen(), null, product());

        assertThat(result.valid())
                .as("errors: %s", result.errors())
                .isTrue();
    }

    @Test
    @DisplayName("the success message still works when a product is chosen")
    void theCatalogsOwnMessageIsNotLost() {
        // The two sources are a union, not a replacement. Getting this wrong
        // would break every existing happy-path scenario the moment someone
        // selected a product, which is the kind of regression that looks like
        // the product feature "not working" rather than like a union bug.
        String happyPath = REJECTS_BAD_IBAN.replace(
                "verify \"Geçersiz IBAN numarası girdiniz.\" text is displayed",
                "verify \"Maaş ödeme talimatınız alınmıştır.\" text is displayed");

        assertThat(validator.validate(happyPath, vocabulary(), screen(), null, product()).valid())
                .isTrue();
    }

    @Test
    @DisplayName("a message neither source declares is still an invention")
    void anInventedMessageIsStillRefused() {
        String invented = REJECTS_BAD_IBAN.replace(
                "Geçersiz IBAN numarası girdiniz.", "IBAN hatalı, lütfen kontrol ediniz.");

        ValidationResult result = validator.validate(
                invented, vocabulary(), screen(), null, product());

        assertThat(result.valid()).isFalse();
        // And the suggestion names the sentence the draft most likely meant —
        // the nearest KNOWN message, not just the success one. The analyst
        // fixes it by clicking the chip, so the chip has to carry the right
        // sentence out of a set that now has more than one member.
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.suggestions())
                        .containsExactly("verify \"Geçersiz IBAN numarası girdiniz.\" text is displayed"));
    }

    @Test
    @DisplayName("a value the rule FIXES is assertable, an option it does not list is not")
    void degerlerWidensTheAcceptedSet() {
        // One step further out than `messages`, and for the same reason: a
        // dropdown's options are something the screen PRINTS, so a scenario
        // naming one is quoting a written-down fact. Without this the only
        // way to test "the list offers these four" is to paste the options
        // into the intent -- and on a screen with no catalog at all there is
        // nothing else the check could consult.
        String asserts = REJECTS_BAD_IBAN.replace(
                "Geçersiz IBAN numarası girdiniz.", "Son 12 Ay");

        assertThat(validator.validate(asserts, vocabulary(), screen(), null, withValues()).valid())
                .as("a value the rule lists")
                .isTrue();

        String invented = REJECTS_BAD_IBAN.replace(
                "Geçersiz IBAN numarası girdiniz.", "Son 48 Ay");

        assertThat(validator.validate(invented, vocabulary(), screen(), null, withValues()).valid())
                .as("a value no rule lists")
                .isFalse();
    }

    @Test
    @DisplayName("with no success message anywhere, the analyst's own words still earn it")
    void theIntentEscapeSurvives() {
        // Preserved deliberately. Without a success_message the finding is the
        // older, weaker UNCATALOGUED_MESSAGE, and its escape — the analyst
        // dictated the sentence, so it is knowledge rather than invention —
        // must not disappear just because a product is now selected.
        String dictated = "Devam'a basınca \"Geçersiz IBAN numarası girdiniz.\" çıkmalı";

        ValidationResult result = validator.validate(
                REJECTS_BAD_IBAN, vocabulary(), screenWithoutMessage(), dictated, emptyProduct());

        assertThat(result.valid())
                .as("errors: %s", result.errors())
                .isTrue();
    }

    /* ----------------------------------------------------------------- */

    private static CatalogModel screen() {
        return CatalogModel.parse("""
                app: NGC
                flow: Maas Odemeleri
                fields:
                  - label: "Açıklama"
                    type: text
                success_message: "Maaş ödeme talimatınız alınmıştır."
                """).orElseThrow();
    }

    private static CatalogModel screenWithoutMessage() {
        return CatalogModel.parse("""
                app: NGC
                flow: Maas Odemeleri
                fields:
                  - label: "Açıklama"
                    type: text
                """).orElseThrow();
    }

    private static ProductModel product() {
        return ProductModel.parse("ngc", "maas-odemeleri", ProductModel.Sources.of("""
                app: NGC
                name: "Maaş Ödemeleri"
                """, """
                rules:
                  - id: R-MAAS-010
                    title: "IBAN format kontrolü"
                    status: DOĞRULANDI
                    messages:
                      - "Geçersiz IBAN numarası girdiniz."
                """)).orElseThrow();
    }

    /** A product whose rule fixes a list of options with {@code degerler:}. */
    private static ProductModel withValues() {
        return ProductModel.parse("ngc", "maas-odemeleri", ProductModel.Sources.of("""
                app: NGC
                name: "Maaş Ödemeleri"
                """, """
                rules:
                  - id: R-AL-015
                    title: "Hızlı Tarih Seçim seçenekleri"
                    status: DOĞRULANDI
                    degerler:
                      secenekler: ["Bu Hafta", "Son 1 Ay", "Son 12 Ay"]
                      varsayilan: "Son 1 Ay"
                """)).orElseThrow();
    }

    /** A product that exists but declares no messages at all. */
    private static ProductModel emptyProduct() {
        return ProductModel.parse("ngc", "maas-odemeleri", ProductModel.Sources.of("""
                app: NGC
                name: "Maaş Ödemeleri"
                """, "")).orElseThrow();
    }

    private static Vocabulary vocabulary() {
        return new Vocabulary(List.of(
                "login with cif {string} and userid {string}",
                "navigate to {string} {string}",
                "click {string}",
                "enter {string} {string}",
                "verify {string} text is displayed").stream()
                .map(ProductMessageTest::step)
                .toList());
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w*)}");

    private static VocabularyStep step(String pattern) {
        List<String> parameters = PLACEHOLDER.matcher(pattern).results()
                .map(match -> match.group(1))
                .toList();
        String keyword = pattern.startsWith("verify") ? "Then"
                : pattern.startsWith("login") ? "Given" : "And";
        return new VocabularyStep(pattern, keyword, parameters, "CommonSteps", "step");
    }
}
