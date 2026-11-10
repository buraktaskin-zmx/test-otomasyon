package orchestrator.llm;

import orchestrator.catalog.CatalogModel;
import orchestrator.product.ProductModel;
import orchestrator.product.Rule;
import orchestrator.validation.ScenarioValidator;
import orchestrator.validation.ValidationError;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

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
     * The product context for one generation, and the reason it is a pair.
     *
     * The two halves are used at different moments and must not be collapsed:
     * {@code rules} is what the analyst ticked and it shapes the PROMPT, while
     * {@code product} carries every message the product knows and it shapes
     * the VALIDATOR. Tying the validator to the ticked subset would mean a
     * draft accepted during generation being refused by the identical check at
     * the run gate, because a checkbox was cleared in between.
     */
    public record ProductContext(ProductModel product, List<Rule> rules) {

        /** No product chosen — every path below behaves exactly as before. */
        public static final ProductContext NONE = new ProductContext(null, List.of());
    }


    /**
     * One scenario, from the prompt that asks for it to a verdict on what came
     * back — generate, validate, repair, validate again.
     *
     * Extracted so {@link #suggest} and {@link #suggestMany} share it rather
     * than each carrying a copy. The copies would have been identical on the day
     * they were written and would have drifted at the first prompt change, with
     * the batch path silently keeping the older behaviour.
     */
    private Suggestion one(String system, String userMessage, CatalogModel catalog,
                           ProductModel product, Vocabulary vocabulary, String loginStep,
                           String intent, int repairs) {
        return one(system, userMessage, catalog, product, vocabulary, loginStep, intent,
                repairs, draft -> List.of());
    }

    /**
     * @param extra a check of the caller's own, run beside the validator and
     *              treated exactly like it: its findings make the draft
     *              invalid and reach the repair turn. Empty for every path
     *              except the walk's, where it is the provenance check.
     */
    private Suggestion one(String system, String userMessage, CatalogModel catalog,
                           ProductModel product, Vocabulary vocabulary, String loginStep,
                           String intent, int repairs,
                           Function<String, List<ValidationError>> extra) {
        List<String> log = new ArrayList<>();

        String draft = settle(llm.ask(system, userMessage), loginStep, catalog, vocabulary,
                intent);
        ValidationResult result = check(draft, vocabulary, catalog, intent, product, extra);
        int attempts = 1;
        log.add(describe(attempts, result));

        while (!result.valid() && attempts <= repairs) {
            // The repair turn carries the draft and the verdict, not the
            // original task: the model already knows what it was asked for and
            // repeating it invites a fresh scenario instead of a fix.
            draft = settle(llm.ask(system, prompts.repair(draft, result)), loginStep, catalog,
                    vocabulary, intent);
            result = check(draft, vocabulary, catalog, intent, product, extra);
            attempts++;
            log.add(describe(attempts, result));
        }

        return new Suggestion(draft, result.valid(), result, attempts, log);
    }

    private ValidationResult check(String draft, Vocabulary vocabulary, CatalogModel catalog,
                                   String intent, ProductModel product,
                                   Function<String, List<ValidationError>> extra) {
        ValidationResult result = validator.validate(draft, vocabulary, catalog, intent, product);
        List<ValidationError> more = extra.apply(draft);
        if (more.isEmpty()) {
            return result;
        }
        List<ValidationError> all = new ArrayList<>(result.errors());
        all.addAll(more);
        return ValidationResult.failed(result.stepCount(), all);
    }

    /**
     * A scenario written FROM A RECORDED WALK rather than from intent alone.
     *
     * Same pipeline as {@link #suggest} — the shared system prompt, the same
     * rewriters, the same validator and repair turn — with two differences
     * that are the whole point: the user turn carries the walk as evidence
     * (built by the caller), and {@code provenance} runs beside the validator,
     * so a label or value that appears nowhere in the walk, the catalog or the
     * analyst's text is a finding the model must fix rather than a line that
     * times out in a browser.
     *
     * The catalog is NOT handed to the validator here, only to the model (it
     * is inside {@code userMessage}). A walk crosses screens — the list where
     * the firm is chosen, then the form — and a catalog describes one, so its
     * label check would refuse "Firma Seçimi" on the Fatura Oluştur form's
     * catalog, and the repair turn would then "fix" a line the walk proved
     * right. Which labels exist is answered by the walk itself, through
     * {@code provenance}; grammar, assertions and the rest still run.
     */
    public Suggestion suggestFromWalk(String userMessage, String intent, Vocabulary vocabulary,
                                      ProductContext product,
                                      Function<String, List<ValidationError>> provenance) {
        return one(prompts.system(vocabulary), userMessage, null, product.product(), vocabulary,
                null, intent, maxRepairs, provenance);
    }

    /**
     * One scenario written from a DRAWN FLOW — the sibling of
     * {@link #suggestFromWalk}, with one difference that is the whole reason
     * it exists separately: a catalog may be passed.
     *
     * <p>A drawn journey crosses screens, so it goes the walk's way with
     * {@code catalog = null} and leans on {@code provenance}. But a scenario
     * whose path is a SINGLE screen, and whose screen has a saved catalog, is
     * an ordinary single-screen scenario: handing the catalog over turns the
     * six catalog checks back on — labels, verbs, options, entry row, earned
     * assertions, paraphrased messages — which is most of the validator's
     * value and far better than a substring check over the drawing.
     *
     * <p>The caller decides which it is; this method does not guess.
     */
    public Suggestion suggestFromDrawing(String userMessage, String intent, Vocabulary vocabulary,
                                         CatalogModel catalog, ProductContext product,
                                         String loginStep,
                                         Function<String, List<ValidationError>> provenance) {
        return one(prompts.system(vocabulary), userMessage, catalog, product.product(), vocabulary,
                loginStep, intent, maxRepairs, provenance);
    }

    /** A plain model call for the planning turn, which produces no Gherkin. */
    public String ask(String system, String user) {
        return LlmOutputCleaner.clean(llm.ask(system, user));
    }

    /**
     * Everything done TO a draft between the model answering and the validator
     * reading it, in the order it has to happen.
     *
     * Collected into one method because it is applied twice — after the first
     * generation and after every repair — and the two copies had already started
     * to differ once: LoginStep was on both, DateVerb would have gone on one.
     * A repair turn that skipped a correction would hand back a draft worse than
     * the one it was fixing.
     *
     * Order is load-bearing. Cleaning strips the reasoning block and the code
     * fence, so it must come first or the patterns below match inside them.
     * LoginStep rewrites the first step, DateVerb the field steps, EntryRow one
     * navigation step; no two of them touch the same line. EntryRow may DELETE a
     * line, which shifts the numbers the validator reports -- harmless only
     * because it runs before the validation, never between its passes.
     *
     * ReadOnlyAssertion goes LAST because it is the only one that APPENDS: it
     * has to find the final step of the finished draft, and the rewriters above
     * it can still be changing lines. It is also the only one that reads the
     * analyst's intent, which is what tells it which field to assert about.
     *
     * OutlineFlattener used to sit between the two. It resolved a Scenario
     * Outline's placeholders FROM THE BATCH PLAN, so it only ever did anything
     * when a variation was passed -- on every other path it read the null and
     * stood aside. With the batch path gone there is no plan to resolve against
     * and nothing it could do, so it went with it.
     */
    private static String settle(String answer, String loginStep, CatalogModel catalog,
                                 Vocabulary vocabulary, String intent) {
        return ReadOnlyAssertion.apply(
                EntryRow.apply(
                        DateVerb.apply(
                                LoginStep.apply(LlmOutputCleaner.clean(answer), loginStep),
                                catalog, vocabulary),
                        catalog, vocabulary),
                catalog, vocabulary, intent);
    }


    private static String describe(int attempt, ValidationResult result) {
        return "attempt " + attempt + ": "
                + (result.valid()
                ? "valid, " + result.stepCount() + " steps"
                : result.errors().size() + " error(s), " + result.stepCount() + " steps");
    }}
