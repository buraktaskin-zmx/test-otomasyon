package orchestrator.chat;

import orchestrator.validation.ValidationError;
import orchestrator.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The finding-to-question mapping.
 *
 * The case that matters most is the first one: a read-only field the request
 * named but gave no expected value for. Today the model answers that by
 * inventing a value or deleting the assertion — measured, twice, in one
 * session. The analyst can answer it in one click, and these tests pin the two
 * clicks they are offered.
 */
class ChatQuestionsTest {

    private static ValidationResult failedWith(ValidationError... errors) {
        return ValidationResult.failed(6, List.of(errors));
    }

    private static ValidationError unearned() {
        return ValidationError.unearnedAssertion(
                9, "verify the \"Bayi Ünvanı\" field equals \"Ford Otomobil\"",
                "Bayi Ünvanı", "Ford Otomobil",
                List.of("verify the {string} field is masked"));
    }

    @Test
    @DisplayName("a valid scenario raises no question")
    void nothingToAsk() {
        assertThat(ChatQuestions.resolve(ValidationResult.ok(6))).isEmpty();
        assertThat(ChatQuestions.resolve(null)).isEmpty();
    }

    @Test
    @DisplayName("a read-only field offers exactly the two legal routes")
    void readOnlyFieldAsksTheMockupQuestion() {
        ChatQuestions.Resolution pending = ChatQuestions.resolve(failedWith(unearned())).orElseThrow();

        assertThat(pending.question().text())
                .contains("Bayi Ünvanı")
                .contains("salt okunur");
        assertThat(pending.question().options())
                .extracting(ChatQuestions.ChatOption::label)
                .containsExactly("Beklenen değeri ben gireyim", "Doğrulamayı atla");

        // One of them cannot be answered by a click alone.
        assertThat(pending.question().options().get(0).needsValue()).isTrue();
        assertThat(pending.question().options().get(1).needsValue()).isFalse();

        // Free text is off: both honest answers are already on screen, and a
        // third route would be the analyst writing something the validator
        // then refuses again.
        assertThat(pending.question().allowsFreeText()).isFalse();
    }

    @Test
    @DisplayName("the chosen route becomes a sentence appended to the request")
    void answersBecomeRequestText() {
        ChatQuestions.Resolution pending = ChatQuestions.resolve(failedWith(unearned())).orElseThrow();

        assertThat(ChatQuestions.applied(pending, "value", "TEST / TEST UNVANI"))
                .isEqualTo("\"Bayi Ünvanı\" alanı \"TEST / TEST UNVANI\" yazmalı.");
        assertThat(ChatQuestions.applied(pending, "skip", null))
                .isEqualTo("\"Bayi Ünvanı\" alanını doğrulama.");
    }

    @Test
    @DisplayName("an answer that resolves to nothing is refused, not guessed at")
    void unanswerableAnswersReturnNull() {
        ChatQuestions.Resolution pending = ChatQuestions.resolve(failedWith(unearned())).orElseThrow();

        // A stale button from a question that has since been replaced.
        assertThat(ChatQuestions.applied(pending, "bogus", "x")).isNull();
        // The route that needs a value, chosen without one.
        assertThat(ChatQuestions.applied(pending, "value", "  ")).isNull();
        assertThat(ChatQuestions.applied(null, "value", "x")).isNull();
    }

    @Test
    @DisplayName("an unknown label offers the catalog's own nearest labels")
    void unknownLabelOffersRealLabels() {
        ChatQuestions.Resolution pending = ChatQuestions.resolve(failedWith(
                ValidationError.unknownLabel(4, "enter \"Bayi Kod\" \"93002\"", "Bayi Kod",
                        List.of("Bayi Kodu", "Bayi Ünvanı")))).orElseThrow();

        assertThat(pending.question().options())
                .extracting(ChatQuestions.ChatOption::label)
                .containsExactly("Bayi Kodu", "Bayi Ünvanı");
        assertThat(ChatQuestions.applied(pending, "near0", null))
                .isEqualTo("\"Bayi Kod\" yerine \"Bayi Kodu\" alanını kullan.");
        // None of them may be right -- the analyst can say so.
        assertThat(pending.question().allowsFreeText()).isTrue();
    }

    @Test
    @DisplayName("a kind with no known answers shows the validator's own words and asks")
    void unknownKindsFallBackToFreeText() {
        ChatQuestions.Resolution pending = ChatQuestions.resolve(failedWith(
                ValidationError.undefinedStep(7, "Tutar bilgisini giriniz",
                        List.of("enter amount {string} {string}")))).orElseThrow();

        // No invented buttons: this class does not know what the legal
        // answers are here, and pretending to would be the guessing it exists
        // to prevent.
        assertThat(pending.question().options()).isEmpty();
        assertThat(pending.question().allowsFreeText()).isTrue();
        assertThat(pending.question().text())
                .contains("Tanımsız adım")
                .contains("Tutar bilgisini giriniz");
    }

    @Test
    @DisplayName("one question at a time, and it is the first finding")
    void onlyTheFirstFindingIsAsked() {
        ChatQuestions.Resolution pending = ChatQuestions.resolve(failedWith(
                ValidationError.unknownLabel(4, "step", "Bayi Kod", List.of("Bayi Kodu")),
                unearned())).orElseThrow();

        // Answering the first usually changes the rest -- a corrected label
        // often takes the assertion that depended on it along too.
        assertThat(pending.question().text()).contains("Bayi Kod");
        assertThat(pending.question().text()).doesNotContain("salt okunur");
    }
}
