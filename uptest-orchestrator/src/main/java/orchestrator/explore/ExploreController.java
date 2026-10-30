package orchestrator.explore;

import orchestrator.catalog.CatalogService;
import orchestrator.llm.LlmOutputCleaner;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static orchestrator.explore.AgentPrompts.*;

/**
 * THROWAWAY EXPERIMENT — not part of the run pipeline.
 *
 * Answers one question before anything is built on top of it: can the model
 * actually drive this bank's screens through Playwright MCP? Generating a
 * Gherkin file in one shot and running a multi-turn tool loop are different
 * skills, and the second one is unproven here.
 *
 * Off unless switched on (uptest.explore.enabled=true), because it drives a
 * REAL browser against the REAL test bank: every click is a live action, and
 * an endpoint that does that should not exist by accident.
 *
 * The tools come from the MCP server declared in .mcp.json — Spring AI
 * discovers them (spring.ai.mcp.client.toolcallback.enabled defaults to true)
 * and hands the model browser_navigate, browser_click, browser_snapshot and
 * the rest. The list is never enumerated here, so adding a tool to the MCP
 * server needs no change in this class.
 *
 * A few names are the exception, each for a reason that does not generalise:
 * the two in WITHHELD, which must be kept AWAY from the model; CLOSE, which
 * this class calls ITSELF; and the click/select pair DateTool drives on the
 * model's behalf. A name is written down only where the code has to single
 * that tool out; everything else arrives anonymously.
 */
@RestController
@RequestMapping("/api/v1/explore")
@ConditionalOnProperty(name = "uptest.explore.enabled", havingValue = "true")
public class ExploreController {


    /**
     * Built ONCE. The tools are not on it — they go on each request.
     *
     * They used to be, via defaultTools on a builder held in a field, and that
     * builder is shared: defaultTools APPENDS rather than replaces, so the
     * second exploration of an application's lifetime handed the model every
     * tool twice and Spring AI refused the call with "Multiple tools with the
     * same name". The first run of every restart worked, which is what made it
     * look like something other than what it was.
     */
    private final ChatClient client;

    private final ToolCallbackProvider tools;
    private final CatalogService catalogs;

    /**
     * Tools the model is NOT given, however useful they look.
     *
     * Both run JavaScript. Offered them, the model stopped clicking and
     * started writing code — and then looped on its own errors
     * ("SyntaxError: Unexpected token ';'", "ReferenceError: require is not
     * defined"), never reaching an answer. Exploration needs reading and
     * clicking, not scripting.
     *
     * browser_run_code_unsafe is also RCE-equivalent by its own description,
     * which is not something to hand a model inside a bank.
     */
    private static final Set<String> WITHHELD =
            Set.of("browser_run_code_unsafe", "browser_evaluate");

    /**
     * A last resort, not the way the agent is meant to stop.
     *
     * What actually ends a run is finishing the flow; what stops a runaway one
     * is BudgetedTool refusing to navigate back to a page already visited.
     * This ceiling only catches a loop neither of those recognises, so it is
     * set well above a real flow: the DBS invoice walk is a login (~6 calls),
     * a menu walk (~4), thirteen fields each read before being filled (~26)
     * and three submits with their snapshots (~8) — about fifty. Reaching 250
     * means something is wrong that raising the number will not fix.
     */
    private final int budget;

    /**
     * Where each walk's action tape is written.
     *
     * Under {@code runs/} on purpose: that directory is already gitignored and
     * is where the MCP server puts its own artifacts, so a tape is filed with
     * the rest of a run's evidence rather than somewhere new that has to be
     * explained and excluded.
     */
    private final Path tapeDir;

    public ExploreController(ChatClient.Builder builder, ToolCallbackProvider tools, CatalogService catalogs,
                             @Value("${uptest.explore.tool-budget:250}") int budget,
                             @Value("${uptest.explore.tape-dir:./runs/mcp}") String tapeDir) {
        this.client = builder.build();
        this.tools = tools;
        this.catalogs = catalogs;
        this.budget = budget;
        this.tapeDir = Paths.get(tapeDir);
    }

    /**
     * The tools for ONE request, each counted against that request's budget.
     *
     * Assembled per call and never stored, because the budget and the
     * visited-page set belong to a single exploration: sharing them would
     * leave the second run of the day already spent. Assembling them is
     * building records — the expensive parts (the model connection, the MCP
     * session) are shared.
     *
     * They are passed to the REQUEST, not set as defaults on the builder. The
     * builder is shared and its tool methods append, so setting them there
     * grew the list on every call until Spring AI rejected it for duplicate
     * names.
     *
     * @param sink        when given, the model also gets the catalog tools and
     *                    answers by calling them; null for the prose endpoint
     * @param instruction the analyst's text, read for off-limits controls
     * @param tape        this request's action tape; every wrapped tool writes
     *                    to it, which is what makes the walk replayable later
     */
    private Object[] toolsFor(CatalogSink sink, String instruction, Set<String> pending,
                              AtomicBoolean aborted, ActionTape tape) {
        List<ToolCallback> usable = new ArrayList<>(Arrays.stream(tools.getToolCallbacks())
                .filter(callback -> !WITHHELD.contains(callback.getToolDefinition().name()))
                .toList());

        // Bound to the RAW callbacks: set_date makes its four browser calls
        // itself, and wrapping those would count one date against the budget
        // four times and read them as the model's own moves.
        DateTool dates = DateTool.bind(tools.getToolCallbacks());

        // Ordinary @Tool objects, so they go through the same budget wrapper
        // as the browser tools and need no special case.
        //
        // set_date is offered on EVERY walk, not only the catalog one. It sat
        // behind `sink != null`, so "AI ile çalıştır" — which has no sink —
        // told the agent in the shared prompt that dates are set with
        // set_date and never gave it the tool. The agent then typed into the
        // masked box ("81/02/0262" for 28/10/2026), got refused, and drove the
        // calendar by hand, which is the slow and flaky route the tool exists
        // to replace.
        List<Object> ours = new ArrayList<>();
        if (sink != null) {
            ours.add(sink);
        }
        if (dates != null) {
            ours.add(dates);
        }
        if (!ours.isEmpty()) {
            usable.addAll(List.of(MethodToolCallbackProvider.builder()
                    .toolObjects(ours.toArray()).build().getToolCallbacks()));
        }

        return BudgetedTool.wrap(usable, budget, sink, forbidden(instruction), pending, instruction,
                aborted, tape);
    }

    /**
     * The values a tape must not keep.
     *
     * Only the two that are genuinely credentials. The cif and the user id are
     * left alone on purpose — see {@link ActionTape}'s note on why masking a
     * four-digit user id damages the artifact more than it protects anything.
     */
    private static List<String> secretsOf(Instruction body) {
        return List.of(
                body.password() == null ? "" : body.password(),
                body.smsCode() == null ? "" : body.smsCode());
    }

    /**
     * Controls the analyst marked as off limits, one per comma.
     *
     * A deliberate convention rather than a guess at meaning: a line that
     * begins "TIKLAMA:" lists captions the agent must not touch. Reading the
     * instruction for negations in prose would be guessing at language, and
     * would fail silently the first time someone phrased it differently.
     *
     *   TIKLAMA: Garantili Ödeme, Fatura numarası eklemek istiyorum
     *
     * Opt-in: without the line nothing is blocked, which is the right default
     * for a guard that can only ever refuse.
     */
    static List<String> forbidden(String instruction) {
        return captions(FORBIDDEN_LINE, instruction);
    }

    /**
     * Controls the analyst requires the agent to touch, one per comma.
     *
     * The mirror of TIKLAMA, and it exists for a failure that is worse than
     * pressing the wrong thing: told in a numbered step to switch to a named
     * list, the agent skipped it silently, then picked the right row of the
     * WRONG list and catalogued it. Nothing errored.
     *
     *   ZORUNLU: Ödenecekler
     *
     * Not enforced — a tool call cannot be summoned from here. What it buys is
     * that the omission is REPORTED instead of being something a human
     * happens to notice by matching a reference number against a screenshot.
     */
    static List<String> required(String instruction) {
        return captions(REQUIRED_LINE, instruction);
    }

    private static List<String> captions(Pattern marker, String instruction) {
        List<String> captions = new ArrayList<>();
        if (instruction == null) {
            return captions;
        }
        Matcher line = marker.matcher(instruction);
        while (line.find()) {
            for (String caption : line.group(1).split(",")) {
                if (!caption.isBlank()) {
                    captions.add(caption.trim());
                }
            }
        }
        return captions;
    }

    private static final Pattern REQUIRED_LINE =
            Pattern.compile("(?ium)^\\s*ZORUNLU\\s*:\\s*(.+)$");

    /**
     * Both dotted and dotless I, spelled out in the class rather than left to
     * case folding: the marker is a Turkish word, and no locale-blind
     * case-insensitive match folds "I" onto "ı". (?u) turns case folding on
     * for the non-ASCII letters in the captions themselves.
     */
    private static final Pattern FORBIDDEN_LINE =
            Pattern.compile("(?ium)^\\s*T[Iıi]KLAMA\\s*:\\s*(.+)$");

    /**
     * The one tool this class calls itself, so the one it has to name.
     *
     * Ending the model loop does not end the browser: the MCP server is a
     * long-lived process started with the application, and it keeps its page
     * open wherever the last run abandoned it. The next exploration then starts
     * on the previous one's result screen instead of a blank tab, which makes
     * two runs of the same instruction two different runs.
     *
     * Closing it is OURS to do, not the model's. Told to close the browser, an
     * agent forgets, or closes it early and then needs it again. A finally
     * block does not.
     */
    private static final String CLOSE = "browser_close";

    /**
     * Leaves the browser in a known state for the next run.
     *
     * Never throws: this runs after the catalog is already built, and losing a
     * finished catalog because the cleanup failed would be absurd. A missing
     * tool is reported rather than ignored — silence would look exactly like a
     * successful close.
     *
     * Note this closes the PAGE, not the profile. Session cookies live in
     * --user-data-dir on disk and survive, so a following run may find itself
     * already logged in.
     */
    private void closeBrowser() {
        for (ToolCallback callback : tools.getToolCallbacks()) {
            if (CLOSE.equals(callback.getToolDefinition().name())) {
                try {
                    callback.call("{}");
                } catch (Exception failed) {
                    System.out.println("[uptest] explore: tarayici kapatilamadi — "
                            + failed.getClass().getSimpleName() + ": " + failed.getMessage());
                }
                return;
            }
        }
        System.out.println("[uptest] explore: '" + CLOSE + "' diye bir arac yok; tarayici acik "
                + "kaliyor ve sonraki kosu onceki ekrandan basliyor");
    }

    /**
     * What the analyst typed, with the login split out of it.
     *
     * Credentials used to be pasted into the free text every single run, which
     * made every run's login wording slightly different and put a CIF and a
     * password into the middle of a paragraph. They are their own fields now;
     * {@link #compose} writes the preamble, identically every time.
     *
     * Every field except {@code text} may be null — the older UI sent only
     * that, and a flow that is already logged in needs none of them.
     */
    public record Instruction(String text, String url, String cif, String userId,
                              String password, String smsCode, String mainMenu,
                              List<String> expected) {
    }

    /**
     * The whole instruction the agent is given: login preamble, then the
     * analyst's steps.
     *
     * This composed text — not the raw textarea — is what the guards read.
     * That is load-bearing rather than tidy: BudgetedTool refuses to touch a
     * field the instruction never mentions, and the login boxes are fields. On
     * the raw text they would be strangers and the agent would be blocked from
     * logging in at all.
     */
    private static String compose(Instruction body) {
        StringBuilder out = new StringBuilder();

        if (present(body.url())) {
            out.append(body.url()).append(" adresine git.\n");
        }
        if (present(body.cif()) || present(body.userId()) || present(body.password())) {
            out.append("Giriş yap:\n");
            append(out, "Firma Kodu / Müşteri Kodu", body.cif());
            append(out, "Kullanıcı Kodu", body.userId());
            append(out, "Şifre", body.password());
            out.append("- #btnSubmit\n");
        }
        if (present(body.smsCode())) {
            out.append("Akıllı SMS ekranı gelirse #otpPassword alanına ")
                    .append(body.smsCode().trim())
                    .append(" yaz, tekrar giriş yap.\n");
        }
        if (present(body.mainMenu())) {
            out.append("Ana menü: ").append(body.mainMenu().trim()).append("\n");
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        return out.append(body.text().trim()).toString();
    }

    /**
     * 409 with a fixed code the UI recognises, so it can say what happened.
     *
     * Carries the tape id like every other answer: a walk that was cut short
     * is exactly the one whose recorded calls are worth reading, and it is the
     * one that leaves no prose behind to read instead.
     */
    private static ResponseEntity<?> browserClosed(ActionTape tape) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "BROWSER_CLOSED",
                "detail", "Tarayıcı beklenmedik şekilde kapandı",
                "tapeId", tape.id()));
    }

    private static void append(StringBuilder out, String label, String value) {
        if (present(value)) {
            out.append("- ").append(label).append(" = ").append(value.trim()).append('\n');
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @PostMapping
    public ResponseEntity<?> explore(@RequestBody Instruction body) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        String instruction = compose(body);
        AtomicBoolean aborted = new AtomicBoolean();
        ActionTape tape = ActionTape.open(tapeDir, "explore", instruction,
                body.url(), body.mainMenu(), secretsOf(body));
        try {
            String answer = client.prompt()
                    .system(SYSTEM + ANSWER_RULES)
                    .user(instruction)
                    .tools(toolsFor(null, instruction, new LinkedHashSet<>(), aborted, tape))
                    .call()
                    .content();
            // Belt and braces: should the framework ever turn BrowserClosed
            // into a tool result, the flag still says the walk was cut short.
            if (aborted.get()) {
                return browserClosed(tape);
            }

            // The walk, written back out as steps. A PROPOSAL, never a save:
            // an agent that skipped a step produces a scenario that skips it
            // too, and this run's own tape caught exactly that happening. The
            // analyst reads the lines and decides.
            TapeRenderer.Rendered rendered =
                    TapeRenderer.render(tape.calls(), body.mainMenu(), body.expected());
            return ResponseEntity.ok(Map.of(
                    "answer", answer,
                    "tapeId", tape.id(),
                    "gherkin", String.join("\n", rendered.steps()),
                    "notes", rendered.notes()));
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof BudgetedTool.BrowserClosed || aborted.get()) {
                    return browserClosed(tape);
                }
                if (cause.getCause() == cause) {
                    break;
                }
            }
            // The model, the MCP server and the browser are three separate
            // processes; any of them can be the one that failed. Report the
            // root cause rather than a 500 page that names none of them.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return ResponseEntity.status(502).body(Map.of(
                    "error", root.getClass().getSimpleName(),
                    "detail", String.valueOf(root.getMessage()),
                    "tapeId", tape.id()));
        } finally {
            // Stamped before the browser goes, and on every path: the tape of
            // a run that died is the one worth reading.
            tape.finish();
            closeBrowser();
        }
    }

    /**
     * Explore a flow and come back with a catalog draft instead of prose.
     *
     * One model turn, not two: the agent already is an LLM, so asking it for
     * prose and then translating that prose to YAML with a second call would
     * add a lossy layer for nothing.
     *
     * The schema is read from catalogs/SCHEMA.md rather than restated here —
     * the same discipline as PromptBuilder taking the step vocabulary from a
     * live executor. A hand-copied schema is a second source of truth and
     * drifts the first time someone adds a field key.
     *
     * The draft is a DRAFT. An accessibility snapshot cannot tell Select2 v3
     * from v4 (they render identically and only one has a step that drives
     * it), cannot see that an amount field is two inputs, and cannot tell a
     * datepicker from a text box — so the type column is the part to check
     * against a real scan before trusting it.
     */
    @PostMapping("/catalog")
    public ResponseEntity<?> catalog(@RequestBody Instruction body) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }

        String schema;
        try {
            schema = Files.readString(catalogs.root().resolve("SCHEMA.md"), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "catalogs/SCHEMA.md okunamadi",
                    "detail", String.valueOf(missing.getMessage())));
        }

        // Composed before the try so the tape — which the finally stamps — is
        // in scope on every path out of here.
        String instruction = compose(body) + CLOSING;
        ActionTape tape = ActionTape.open(tapeDir, "catalog", instruction,
                body.url(), body.mainMenu(), secretsOf(body));

        try {
            CatalogSink sink = new CatalogSink();
            // Emptied by BudgetedTool as each caption is touched; whatever is
            // left when the run ends was skipped.
            Set<String> skipped = new LinkedHashSet<>(required(instruction));

            String spoken = client.prompt()
                    .system(SYSTEM + CATALOG_RULES + schema)
                    .user(instruction)
                    .tools(toolsFor(sink, instruction, skipped, new AtomicBoolean(), tape))
                    .call()
                    .content();

            // Preferred path: the model called submit_catalog and the YAML was
            // rendered from its arguments, so it conforms by construction.
            // Fallback: it answered in text instead — older behaviour, kept
            // because a refusal to answer at all is worse than a messy answer.
            String catalog = sink.yaml();
            List<String> problems;

            if (catalog != null) {
                problems = problems(catalog, sink.emptyChoiceReasons());
                if (!sink.missedOnConfirmation().isEmpty()) {
                    // Invisible in the YAML — a catalog missing a field looks
                    // exactly like a screen that does not have it.
                    problems.add("Onay ekraninda su etiketler vardi ama hicbir alan olarak "
                            + "kaydedilmemis: " + String.join(", ", sink.missedOnConfirmation())
                            + ". Ekranda bu alanlar var ve katalog onlari bilmiyor.");
                }
            } else if (sink.partialYaml() != null) {
                // It noted fields but never closed. Better than nothing, and
                // saying so is what keeps it from looking like a finished one.
                catalog = sink.partialYaml();
                problems = problems(catalog, sink.emptyChoiceReasons());
                problems.add("Model submit_catalog'u cagirmadi; yalnizca note_field ile "
                        + "kaydettikleri var. navigation ve success_message eksik.");
            } else {
                catalog = yamlOnly(LlmOutputCleaner.clean(spoken));
                problems = problems(catalog, sink.emptyChoiceReasons());
                problems.add("Model hicbir katalog aracini cagirmadi; katalog metninden "
                        + "cikarildi. Bicim hatalari bundan kaynaklanabilir.");
            }
            if (!skipped.isEmpty()) {
                // The quietest failure of all: every later step succeeds, so
                // the catalog looks finished and describes the wrong screen.
                problems.add("Talimat su kontrollere dokunulmasini istedi, ajan hicbirini "
                        + "denemedi: " + String.join(", ", skipped)
                        + ". Bu adim atlandiysa sonraki her sey DOGRU ama YANLIS ekranda "
                        + "yapilmis olabilir — katalogu kullanmadan once kontrol edin.");
            }
            return ResponseEntity.ok(Map.of(
                    "catalog", catalog, "problems", problems, "tapeId", tape.id()));
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return ResponseEntity.status(502).body(Map.of(
                    "error", root.getClass().getSimpleName(),
                    "detail", String.valueOf(root.getMessage()),
                    "tapeId", tape.id()));
        } finally {
            // Also on the failure paths: a run that died halfway leaves the
            // browser somewhere stranger than a finished one does.
            tape.finish();
            closeBrowser();
        }
    }


    /**
     * What the MCP server actually offered, WITH each tool's argument schema.
     *
     * Names alone were not enough. Whether browser_click's "target" takes only
     * a snapshot ref or also a selector decides whether navigation can be
     * driven from here at all, and that question was answered twice by
     * guessing and once by reading an error message sideways. The schemas were
     * in memory the whole time.
     *
     * Also worth checking before blaming a prompt: an empty list means the
     * model was never given a browser, and every failure after that would be
     * attributed to the wrong thing.
     */
    @GetMapping("/tools")
    public ResponseEntity<?> tools() {
        List<Map<String, Object>> described = Arrays.stream(tools.getToolCallbacks())
                .map(callback -> callback.getToolDefinition())
                .map(definition -> Map.<String, Object>of(
                        "name", definition.name(),
                        "description", String.valueOf(definition.description()),
                        "inputSchema", String.valueOf(definition.inputSchema())))
                .toList();
        return ResponseEntity.ok(Map.of("count", described.size(), "tools", described));
    }

    /** See {@link CatalogDraftChecks#yamlOnly}. */
    private static String yamlOnly(String text) {
        return CatalogDraftChecks.yamlOnly(text);
    }

    /**
     * See {@link CatalogDraftChecks#problems}. Kept here with its old
     * signature because tests reach it by reflection on this class.
     */
    private static List<String> problems(String yaml, Map<String, String> emptyChoiceReasons) {
        return CatalogDraftChecks.problems(yaml, emptyChoiceReasons);
    }
}
