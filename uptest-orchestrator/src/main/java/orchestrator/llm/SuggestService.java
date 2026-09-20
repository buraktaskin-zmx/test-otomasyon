package orchestrator.llm;

import orchestrator.catalog.CatalogModel;
import orchestrator.validation.ScenarioValidator;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Generate a scenario, check it, and let the model fix its own mistakes.
 *
 * The loop is the whole point. A model asked for Gherkin against a closed
 * vocabulary gets most lines right and one line subtly wrong — "enter amount"
 * written as "enter the amount", a {int} where a {string} belongs. Validation
 * costs milliseconds and launches no browser, so catching that here is free,
 * while catching it at run time costs an analyst four minutes of browser and a
 * failed run they did not cause.
 *
 * A draft that is still invalid after the repair turns is returned ANYWAY,
 * together with its errors: a nearly-right scenario the analyst can fix in the
 * editor beats an error page, and the UI already knows how to render
 * ValidationResult.
 */
@Service
public class SuggestService {

    /**
     * One repair turn by default. The first repair fixes nearly everything it
     * is going to fix; further turns mostly re-word the same mistake and cost
     * another full generation.
     */
    private final int maxRepairs;

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ScenarioValidator validator;

    public SuggestService(LlmClient llm,
                          PromptBuilder prompts,
                          ScenarioValidator validator,
                          @Value("${llm.max-repairs:1}") int maxRepairs) {
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.maxRepairs = maxRepairs;
    }

    /**
     * @param feature  the draft, cleaned of reasoning blocks and code fences
     * @param attempts how many model calls it took, 1 when it was right first time
     * @param log      one line per attempt, so a bad prompt is visible without
     *                 turning on debug logging
     */
    public record Suggestion(
            String feature,
            boolean valid,
            ValidationResult validation,
            int attempts,
            List<String> log
    ) {
    }

    /**
     * @param loginStep the login line to force into the draft, or null to let
     *                  the model write its own. Applied after every generation,
     *                  including repairs, because the prompt asking for it is a
     *                  request and this is the guarantee.
     */
    public Suggestion suggest(String catalogYaml, String intent, Vocabulary vocabulary, String loginStep) {
        String system = prompts.system(vocabulary);
        String userMessage = prompts.user(catalogYaml, intent, loginStep);
        List<String> log = new ArrayList<>();

        // Parsed once, then used by every validation pass including repairs.
        // Empty when the catalog will not parse — the checks simply do not run,
        // which is how this worked before they existed.
        CatalogModel catalog = CatalogModel.parse(catalogYaml).orElse(null);

        String draft = LoginStep.apply(LlmOutputCleaner.clean(llm.ask(system, userMessage)), loginStep);
        ValidationResult result = validator.validate(draft, vocabulary, catalog);
        int attempts = 1;
        log.add(describe(attempts, result));

        while (!result.valid() && attempts <= maxRepairs) {
            // The repair turn carries the draft and the verdict, not the
            // original task: the model already knows what it was asked for and
            // repeating it invites a fresh scenario instead of a fix.
            draft = LoginStep.apply(
                    LlmOutputCleaner.clean(llm.ask(system, prompts.repair(draft, result))), loginStep);
            result = validator.validate(draft, vocabulary, catalog);
            attempts++;
            log.add(describe(attempts, result));
        }

        return new Suggestion(draft, result.valid(), result, attempts, log);
    }

    private static String describe(int attempt, ValidationResult result) {
        return "attempt " + attempt + ": "
                + (result.valid()
                ? "valid, " + result.stepCount() + " steps"
                : result.errors().size() + " error(s), " + result.stepCount() + " steps");
    }
}
