package orchestrator.llm;

import orchestrator.validation.ValidationError;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns the deployed step vocabulary, a screen catalog and an analyst's intent
 * into the two prompts the model sees.
 *
 * The vocabulary is never hardcoded here: it arrives from
 * {@link Vocabulary}, which the orchestrator fetched from a live executor. A
 * hand-written list in this file would be a third copy of the step catalogue
 * and would drift the first time someone adds a step.
 */
@Component
public class PromptBuilder {

    /**
     * The rules the model must not break. Written as constraints rather than
     * suggestions because the validator enforces exactly these, and every
     * violation costs a repair round trip.
     */
    public String system(Vocabulary vocabulary) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You write Cucumber/Gherkin test scenarios for a Turkish internet banking
                application. You are given a CLOSED vocabulary of step patterns and a
                catalog describing one screen.

                HARD RULES — a scenario breaking any of these is rejected automatically:

                1. Use ONLY the step patterns listed below. Never invent a step, never
                   reword one, never translate one. The text outside the quotes must
                   match a pattern character for character.
                2. {string} placeholders are replaced by text in DOUBLE QUOTES.
                   {int} placeholders are replaced by a bare integer.
                3. Every value you put in quotes must come from the catalog: a field
                   label, a button caption, a menu name, a listed option or account.
                   Invent ONLY test data: amounts, dates, names, tax numbers.
                   An account number is NOT test data -- copy one from the field's
                   "accounts" list character for character, because the step matches
                   it against the account numbers the screen really offers. Neither
                   is a confirmation message: assert one only when the catalog gives
                   a success_message, and otherwise end the scenario without it.
                4. Output the .feature file and NOTHING else. No explanation, no
                   commentary, no markdown code fences, no <think> blocks.
                5. Start with "Feature:" and use exactly one "Scenario:" unless the
                   intent clearly asks for several input rows, in which case use one
                   "Scenario Outline:" with an "Examples:" table.
                6. Feature and Scenario titles are written in Turkish. Step lines keep
                   the English pattern text exactly as listed.
                7. A scenario starts by logging in and navigating to the screen, then
                   fills fields, then presses the buttons, then verifies. The
                   catalog's "app" key decides WHICH login step: NGC is corporate
                   and takes cif AND userid, NGI is the individual app and takes a
                   customer code only. They are different applications, so the NGI
                   step on an NGC catalog logs into the wrong place entirely.
                   If "navigation" has an "entry_row", the sub-menu lands on a
                   result grid and not on a form: pick that row with
                   `select row "<entry_row>"` AFTER `navigate to` and BEFORE the
                   `click "<entry_button>"` that opens the form. Skipping it
                   leaves the form bound to no record, or never opens it at all.
                8. FILL EVERY FIELD the catalog lists, in the order it lists them,
                   unless the intent explicitly says to leave one empty. A banking
                   form rejects a half-filled submission with a modal error and the
                   run is wasted -- a real case: skipping "Ad Soyad / Unvan" produced
                   "uygun musteri bulunmamaktadir (ME7004)" and the scenario hung on
                   the dialog until it timed out.
                   ONE exception: a field marked "readonly: true" is filled by the
                   APPLICATION, not by you. It is a disabled input, so it does not
                   merely refuse the value -- the step waits 30 s for something that
                   will never accept typing. Never `enter` into it; verify it if the
                   intent asks. A real case: "Bayi Ünvanı" is looked up from the
                   chosen "Bayi Kodu".
                   A SECOND exception: a field carrying "shown_when: {field, value}"
                   is not on the screen until that field holds that value. Fill it
                   ONLY in a scenario that also sets that value, and set the value
                   BEFORE filling it. Otherwise leave it out entirely -- filling a
                   field that is not on the page times out. Real case: "E-Fatura
                   Hash Kodu" appears only when "Fatura Tipi" is "E-Fatura".
                9. THE FIELD'S "type" DECIDES THE VERB. Every type, not just the
                   unusual ones -- the validator cannot catch a wrong verb,
                   because `enter "Bayi Kodu" "93002"` is a perfectly valid
                   pattern and only fails in the browser, hunting for a text box
                   that is really a dropdown:
                     text, date      -> enter
                     amount          -> enter amount
                     dropdown        -> select ... from
                     account_picker  -> select account ... from
                   A value that looks like a number ("93002") is still a
                   dropdown option when the catalog says "type: dropdown".
                10. ONLY verify a value the scenario itself entered. A field you
                   never filled holds whatever the application derived -- a real
                   case: "Alıcı Ad Soyad / Unvan" is filled in by the bank from
                   the chosen account, and a scenario asserting the name it had
                   typed into the DIFFERENT field "Ad Soyad / Unvan" failed on a
                   screen where nothing was actually wrong. If you did not type
                   it, do not assert it.
                11. ARGUMENT ORDER. A pattern containing the word "from" takes the
                   VALUE first and the field label last; every other pattern takes
                   the LABEL first and the value second. The validator cannot
                   catch a swap -- both are {string}, so the scenario passes
                   validation and then hunts for a field labelled "200,00" until
                   it times out. Read the pattern, do not assume:
                     enter amount "Ödeme Tutarı" "200,00"        label, value
                     select "Vadesinde Peşin Ödensin" from "Ödeme Talebi Türü"
                                                                value, label

                AVAILABLE STEP PATTERNS:
                """);

        for (VocabularyStep step : vocabulary.steps()) {
            sb.append("  ").append(step.keyword()).append(' ').append(step.pattern()).append('\n');
        }

        sb.append("""

                SHAPE OF A VALID SCENARIO (structure only — use the catalog for values):

                Feature: <Turkish title>

                  Scenario: <Turkish sentence describing what is verified>
                    Given <login step with the credentials given in the intent>
                    And navigate to "<main menu>" "<sub menu>"
                    And enter "<field label>" "<value>"
                    And click "<button>"
                    Then verify "<expected text>" text is displayed
                """);

        return sb.toString();
    }

    /**
     * The screen and the intent, with the model left to write its own login
     * line — the behaviour before the orchestrator started writing that line
     * itself (LoginStep).
     *
     * Kept so a deployment that has not taken the login-step change yet can
     * still pick up newer catalog rules from {@link #system}: the two are
     * independent, and coupling them would mean holding back every prompt fix
     * until the whole orchestrator is upgraded. Deliberately no reference to
     * LoginStep here — this overload has to compile where that class is absent.
     */
    public String user(String catalogYaml, String intent) {
        return user(catalogYaml, intent, null);
    }

    /**
     * The screen, plus what the analyst actually wants tested.
     *
     * @param loginStep when the analyst supplied credentials the orchestrator
     *                  writes this line itself, so the model is told to skip it
     *                  rather than guess an application and a userid
     */
    public String user(String catalogYaml, String intent, String loginStep) {
        String login = (loginStep == null)
                ? ""
                : """

                        THE LOGIN STEP IS ALREADY DECIDED — do not write one, it is
                        inserted for you as the scenario's first step:
                          %s
                        """.formatted(loginStep);

        return """
                SCREEN CATALOG (YAML):
                %s

                WHAT TO TEST:
                %s
                %s
                Write the .feature file now. Output nothing but the file contents.
                """.formatted(catalogYaml, intent, login);
    }

    /**
     * The repair turn. The model gets its own draft back with the validator's
     * verdict, which is cheap — validation costs milliseconds and no browser —
     * and fixes the common failure of a nearly-right step pattern.
     */
    public String repair(String draft, ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("The scenario you produced was rejected by the validator.\n\n")
          .append("YOUR DRAFT:\n")
          .append(draft)
          .append("\n\nERRORS:\n");

        for (ValidationError error : result.errors()) {
            sb.append("  line ").append(error.line()).append(": ").append(error.message());
            if (error.step() != null) {
                sb.append(" -> ").append(error.step());
            }
            List<String> suggestions = error.suggestions();
            if (suggestions != null && !suggestions.isEmpty()) {
                sb.append("\n      closest valid patterns: ").append(String.join(" | ", suggestions));
            }
            sb.append('\n');
        }

        sb.append("""

                Rewrite the whole file so every step matches an allowed pattern exactly.
                Keep the intent and the values you already chose wherever they were
                valid. Output nothing but the corrected file contents.
                """);
        return sb.toString();
    }
}
