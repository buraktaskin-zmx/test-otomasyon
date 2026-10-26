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

    /** Upper bound on one batch, so a typed "500" cannot occupy the model for an hour. */
    private final int maxScenarios;

    /** How many scenarios of one batch are generated at the same time. */
    private final int batchConcurrency;

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ScenarioValidator validator;
    private final VariationPlanner planner;

    public SuggestService(LlmClient llm,
                          PromptBuilder prompts,
                          ScenarioValidator validator,
                          VariationPlanner planner,
                          @Value("${llm.max-repairs:1}") int maxRepairs,
                          @Value("${llm.max-scenarios:10}") int maxScenarios,
                          @Value("${llm.batch-concurrency:4}") int batchConcurrency) {
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.planner = planner;
        this.maxRepairs = maxRepairs;
        this.maxScenarios = maxScenarios;
        this.batchConcurrency = Math.max(1, batchConcurrency);
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
     * @param loginStep the login line to force into the draft, or null to let
     *                  the model write its own. Applied after every generation,
     *                  including repairs, because the prompt asking for it is a
     *                  request and this is the guarantee.
     * @param product   the chosen product and its ticked rules, or
     *                  {@link ProductContext#NONE}
     */
    public Suggestion suggest(String catalogYaml, String intent, Vocabulary vocabulary, String loginStep,
                              ProductContext product) {
        // Parsed once, then used by every validation pass including repairs.
        // Empty when the catalog will not parse — the checks simply do not run,
        // which is how this worked before they existed.
        CatalogModel catalog = CatalogModel.parse(catalogYaml).orElse(null);

        // No variation: this is the single-scenario path, where the analyst's
        // intent really may ask for several input rows and a Scenario Outline
        // is the right answer. OutlineFlattener reads that null and stands
        // aside.
        return one(prompts.system(vocabulary),
                prompts.user(catalogYaml, intent, loginStep, null, product.rules()),
                catalog, product.product(), vocabulary, loginStep, intent, null, maxRepairs);
    }

    /**
     * One generation, and whatever the validator makes of it — no repair turn.
     *
     * For the chat, where a finding is not an error to be fixed behind the
     * analyst's back but a QUESTION to put to them. The repair turn is the
     * wrong instinct there, and this session proved how wrong: asked to verify
     * a read-only field, the model first invented a value and then, shown the
     * complaint, deleted the assertion — two guesses, both wrong, neither one
     * the analyst would have made. The honest move is to stop at the first
     * finding and ask.
     *
     * Deliberately the same pipeline otherwise: same prompt, same rewriters in
     * {@link #settle}, same validator. The chat must not quietly produce a
     * different class of scenario from the one the generator panel produces.
     */
    public Suggestion suggestOnce(String catalogYaml, String intent, Vocabulary vocabulary,
                                  String loginStep, ProductContext product) {
        CatalogModel catalog = CatalogModel.parse(catalogYaml).orElse(null);
        return one(prompts.system(vocabulary),
                prompts.user(catalogYaml, intent, loginStep, null, product.rules()),
                catalog, product.product(), vocabulary, loginStep, intent, null, 0);
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
                           String intent, VariationPlanner.Variation variation, int repairs) {
        return one(system, userMessage, catalog, product, vocabulary, loginStep, intent, variation,
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
                           String intent, VariationPlanner.Variation variation, int repairs,
                           Function<String, List<ValidationError>> extra) {
        List<String> log = new ArrayList<>();

        String draft = settle(llm.ask(system, userMessage), loginStep, catalog, vocabulary,
                variation, intent);
        ValidationResult result = check(draft, vocabulary, catalog, intent, product, extra);
        int attempts = 1;
        log.add(describe(attempts, result));

        while (!result.valid() && attempts <= repairs) {
            // The repair turn carries the draft and the verdict, not the
            // original task: the model already knows what it was asked for and
            // repeating it invites a fresh scenario instead of a fix.
            draft = settle(llm.ask(system, prompts.repair(draft, result)), loginStep, catalog,
                    vocabulary, variation, intent);
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
                null, intent, null, maxRepairs, provenance);
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
     * has to find the final step of the finished draft, and OutlineFlattener
     * can still be rewriting lines above it. It is also the only one that reads
     * the analyst's intent, which is what tells it which field to assert about.
     */
    private static String settle(String answer, String loginStep, CatalogModel catalog,
                                 Vocabulary vocabulary, VariationPlanner.Variation variation,
                                 String intent) {
        return ReadOnlyAssertion.apply(
                OutlineFlattener.apply(
                        EntryRow.apply(
                                DateVerb.apply(
                                        LoginStep.apply(LlmOutputCleaner.clean(answer), loginStep),
                                        catalog, vocabulary),
                                catalog, vocabulary),
                        variation),
                catalog, vocabulary, intent);
    }

    /* =========================== batch generation ========================== */

    /**
     * One scenario plus the data combination it was built to exercise.
     *
     * The variation travels back to the UI because it is what makes the list of
     * drafts readable: "E-Fatura + hash kodu" tells an analyst what this one is
     * for, where "Senaryo 3" tells them nothing. It comes from the planner, not
     * from the model's own Scenario title, which is unverified text.
     */
    public record Scenario(int index, String label, Map<String, String> values,
                           List<String> ticked, List<String> excluded, Suggestion suggestion) {
    }

    /**
     * @param notes  what the analyst should know about the plan's limits, possibly empty
     * @param tokens what the whole batch spent, or 0 when the server reported
     *               nothing. Shown rather than hidden because it is the only
     *               number that makes the cost of a batch comparable with the
     *               cost of an agent walk — and until this existed, both were
     *               guesses from prompt sizes.
     */
    public record Batch(List<Scenario> scenarios, List<String> notes, long tokens) {
    }

    /**
     * N scenarios for one screen, each exercising a different combination of the
     * values that screen offers.
     *
     * N SEPARATE documents, not one file holding N scenarios, and the reason is
     * in the validator: {@code collectFromFeature} gathers every scenario's
     * steps into ONE list and {@code checkAssertionsAreEarned} walks it with ONE
     * set of written labels. In a five-scenario file the fifth scenario's
     * `verify` is therefore "earned" by the first scenario's `enter` — the same
     * hole that let an invented "Bayi Ünvanı" reach a browser run. Separate
     * documents keep each check as strict as it is for a single scenario, give
     * each draft its own repair turn so one bad generation cannot poison the
     * rest, and leave each scenario independently runnable.
     *
     * CONCURRENT, up to {@code llm.batch-concurrency}. It was sequential, on the
     * reasoning that one vLLM deployment means five requests share one GPU and
     * finish no sooner — and that reasoning was wrong about the one server it
     * was about. Continuous batching is what vLLM exists for: concurrent
     * requests are folded into the same forward pass. The model makes it
     * starker still — Qwen3.6-35B-A3B is a mixture of experts with 3B active
     * parameters, so a single request is memory-bandwidth bound and leaves the
     * GPU idle. Five at once finishes closer to the time of one than of five.
     *
     * Bounded rather than unbounded because this server is shared: firing a
     * batch of ten in one go pushes every other team's request behind it.
     */
    public Batch suggestMany(String catalogYaml, String intent, Vocabulary vocabulary,
                             String loginStep, int count, ProductContext product) {
        int wanted = Math.max(1, Math.min(count, maxScenarios));
        String system = prompts.system(vocabulary);
        CatalogModel catalog = CatalogModel.parse(catalogYaml).orElse(null);

        List<Scenario> scenarios = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (count > maxScenarios) {
            notes.add("En fazla " + maxScenarios + " senaryo üretilebilir; " + count
                    + " istendi, " + wanted + " üretildi.");
        }

        // No parsed catalog means no variation plan: the planner reads fields,
        // types and option lists, and there are none. The batch still runs —
        // N drafts of the same request, which is what the analyst asked for —
        // and says why they will look alike. Same choice as everywhere else
        // here: an unparseable catalog switches checks OFF, it does not fail.
        VariationPlanner.Plan plan = (catalog == null)
                ? null
                : planner.plan(catalog, wanted);
        if (plan == null) {
            // The reason, not just the fact. "Check the fields block" named neither
            // the line nor the cause, and the causes are not guessable from the
            // outside — a tab in the indent, a colon inside an unquoted Turkish
            // sentence, a field written as a string. CatalogModel.diagnose re-reads
            // the YAML only on this path, so a healthy catalog pays nothing.
            notes.add("Katalog ayrıştırılamadı, bu yüzden veri kombinasyonu planlanamadı: "
                    + wanted + " senaryo da aynı istekten üretildi ve birbirine çok "
                    + "benzeyecek. SEBEP: " + CatalogModel.diagnose(catalogYaml));
        } else {
            notes.addAll(plan.notes());
        }

        // Read before the calls and subtracted after: see LlmClient.spent() for
        // why a counter rather than a per-call return value.
        long before = LlmClient.spent();

        // Indexed, not appended: the tasks finish in whatever order the model
        // answers them, and the analyst's list must still read 1..N.
        Scenario[] done = new Scenario[wanted];
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(batchConcurrency, wanted));
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < wanted; i++) {
                final int index = i;
                final VariationPlanner.Variation variation =
                        (plan == null) ? null : plan.variations().get(i);
                running.add(pool.submit(() -> {
                    Suggestion suggestion = one(
                            system,
                            prompts.user(catalogYaml, intent, loginStep, variation, product.rules()),
                            catalog, product.product(), vocabulary, loginStep, intent,
                            variation, maxRepairs);
                    done[index] = variation == null
                            ? new Scenario(index, "Senaryo " + (index + 1),
                                    Map.of(), List.of(), List.of(), suggestion)
                            : new Scenario(index, variation.label(), variation.values(),
                                    variation.ticked(), variation.excluded(), suggestion);
                }));
            }
            for (int i = 0; i < running.size(); i++) {
                awaitOne(running.get(i), i, done, plan, notes);
            }
        } finally {
            // Per request, never a field: a long-lived pool would hold threads
            // for a batch nobody is waiting on any more, and would still be
            // running them while Spring tries to shut down.
            pool.shutdownNow();
        }

        for (Scenario scenario : done) {
            if (scenario != null) {
                scenarios.add(scenario);
            }
        }

        long tokens = LlmClient.spent() - before;
        System.out.println("[uptest] suggest-many: " + scenarios.size() + "/" + wanted
                + " senaryo, " + tokens + " token");
        return new Batch(scenarios, notes, tokens);
    }

    /**
     * Waits for one scenario and keeps a failure from taking the batch with it.
     *
     * Four drafts an analyst can read beat an error page that mentions none of
     * them, and a batch is exactly the shape where one bad answer should not
     * cost the other four — they are independent model calls with nothing
     * shared between them. The failure is reported as a note naming WHICH
     * scenario died, because a silently shorter list looks like the planner
     * decided to produce fewer.
     */
    private static void awaitOne(Future<?> task, int index, Scenario[] done,
                                 VariationPlanner.Plan plan, List<String> notes) {
        try {
            task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            notes.add((index + 1) + ". senaryo üretimi yarıda kesildi.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            String label = (plan == null)
                    ? "Senaryo " + (index + 1)
                    : plan.variations().get(index).label();
            notes.add((index + 1) + ". senaryo üretilemedi (" + label + "): "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + ". Diğer senaryolar etkilenmedi.");
            System.out.println("[uptest] suggest-many: " + (index + 1) + ". senaryo basarisiz — "
                    + cause);
        }
    }

    private static String describe(int attempt, ValidationResult result) {
        return "attempt " + attempt + ": "
                + (result.valid()
                ? "valid, " + result.stepCount() + " steps"
                : result.errors().size() + " error(s), " + result.stepCount() + " steps");
    }}
