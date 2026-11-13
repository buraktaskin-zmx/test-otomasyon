package orchestrator.explore;

import orchestrator.aitrace.AiTraces;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

    /** How long a page is given to settle after an action, before it is read. */
    private final Duration settle;

    /** "AI ile çalıştır": the token target (a note past 80%, never a stop) and its call ceiling. */
    private final long runTokenBudget;
    private final int runToolBudget;

    /** Characters kept of each tool's description on "AI ile çalıştır" (LeanTools). */
    private final int describeCap;

    /** Characters kept of a summarised tool result on "AI ile çalıştır" (LeanResults). */
    private final int pageCap;

    /** "AI ile çalıştır"'s step cache: a passed walk's steps, replayed in code on the next run. */
    private final AiRunCache cache;

    public ExploreController(ChatClient.Builder builder, ToolCallbackProvider tools,
                             CatalogService catalogs, AiRunCache cache,
                             @Value("${uptest.explore.tool-budget:250}") int budget,
                             @Value("${uptest.explore.tape-dir:./runs/mcp}") String tapeDir,
                             @Value("${uptest.explore.settle-ms:1500}") long settleMs,
                             @Value("${uptest.explore.ai-run.token-budget:100000}") long runTokenBudget,
                             @Value("${uptest.explore.ai-run.tool-budget:40}") int runToolBudget,
                             @Value("${uptest.explore.ai-run.describe-cap:220}") int describeCap,
                             @Value("${uptest.explore.ai-run.page-cap:12000}") int pageCap) {
        this.client = builder.build();
        this.tools = tools;
        this.catalogs = catalogs;
        this.cache = cache;
        this.budget = budget;
        this.tapeDir = Paths.get(tapeDir);
        this.settle = Duration.ofMillis(Math.max(0, settleMs));
        this.runTokenBudget = runTokenBudget;
        this.runToolBudget = runToolBudget;
        this.describeCap = describeCap;
        this.pageCap = pageCap;
    }

    /**
     * The only browser tools "AI ile çalıştır" is offered. Waiting, searching,
     * network and console inspection and screenshots are left out: the measured
     * walk spent 25 of its 52 turns on them, and the page now arrives with
     * every action's result (AiRunPolicy). Navigating is added back only when
     * the code could not log in and the agent has to.
     */
    static final Set<String> AI_RUN_TOOLS = Set.of(
            "browser_snapshot", "browser_click", "browser_type", "browser_fill_form",
            "browser_select_option", "browser_press_key", "browser_handle_dialog",
            // Types into whatever has the focus, with no target. Added
            // 12.11.2026: a dropdown's search box opens at the END of the page,
            // past the part of a long page the agent is shown, so it had no
            // reference to type into and spent seventeen turns inventing one.
            // Once the list is open the box already has the focus.
            "browser_press_sequentially");

    /** The start of Spring's message when the model calls a tool it was not given. */
    static final String UNKNOWN_TOOL = "No ToolCallback found for tool name:";

    private ToolCallback raw(String name) {
        for (ToolCallback callback : tools.getToolCallbacks()) {
            if (name.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
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
                              AtomicBoolean aborted, ActionTape tape, RunVerdict verdict) {
        return toolsFor(sink, instruction, pending, aborted, tape, verdict, null, null, budget, Map.of());
    }

    /**
     * @param policy      "AI ile çalıştır" only; with it the browser tools are
     *                    narrowed to {@code allowed}
     * @param allowed     the browser tools offered, or null for all but WITHHELD
     * @param calls       the tool-call ceiling for this walk
     * @param checkpoints names whose step carries an expectation; do_steps stops after them
     */
    private Object[] toolsFor(CatalogSink sink, String instruction, Set<String> pending,
                              AtomicBoolean aborted, ActionTape tape, RunVerdict verdict,
                              AiRunPolicy policy, Set<String> allowed, int calls,
                              Map<String, String> checkpoints) {
        List<ToolCallback> usable = new ArrayList<>(Arrays.stream(tools.getToolCallbacks())
                .filter(callback -> !WITHHELD.contains(callback.getToolDefinition().name()))
                .filter(callback -> allowed == null || allowed.contains(callback.getToolDefinition().name()))
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
        // An allowed MCP tool the server does not serve is simply absent, and
        // a call to it kills the whole walk (13.11.2026). The one the prompt
        // relies on is provided in code when the MCP lacks it.
        FocusedTyping typing = allowed != null && allowed.contains(FocusedTyping.TOOL)
                ? FocusedTyping.bind(tools.getToolCallbacks()) : null;
        if (typing != null) {
            ours.add(typing);
        }
        if (allowed != null) {
            Set<String> served = new java.util.HashSet<>();
            usable.forEach(callback -> served.add(callback.getToolDefinition().name()));
            List<String> missing = allowed.stream()
                    .filter(name -> !served.contains(name) && !(typing != null && FocusedTyping.TOOL.equals(name)))
                    .sorted().toList();
            System.out.println("[uptest] ai-run: araclar — MCP'de olmayan " + (missing.isEmpty() ? "yok" : missing)
                    + (typing != null ? "; " + FocusedTyping.TOOL + " kodla saglandi" : ""));
        }
        if (verdict != null) {
            ours.add(verdict);
        }
        if (!ours.isEmpty()) {
            usable.addAll(List.of(MethodToolCallbackProvider.builder()
                    .toolObjects(ours.toArray()).build().getToolCallbacks()));
        }
        // "AI ile çalıştır" only: several named steps per turn. Inside the
        // wrap, so the batch is guarded and recorded; bound to the wrapped
        // tools afterwards, so each of its steps is too.
        StepRunner runner = policy == null ? null : new StepRunner().checkpoints(checkpoints);
        if (runner != null) {
            usable.add(runner);
        }
        Object[] wrapped = BudgetedTool.wrap(usable, calls, sink, forbidden(instruction), pending, instruction,
                aborted, tape, verdict, policy);
        if (runner != null) {
            runner.bind(wrapped, tape);
        }
        return wrapped;
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
     *
     * The last six come from Kayıtlı Senaryolar' "AI ile çalıştır"
     * ({@code source = "ai-run"}): they label the AI session (Token Takibi),
     * and {@code screen} — the screen's name as its menu shows it — is where
     * AiRunPrelude navigates before the agent starts. Ekran Keşfi sends none.
     */
    public record Instruction(String text, String url, String cif, String userId,
                              String password, String smsCode, String mainMenu,
                              List<String> expected,
                              String source, String app, String product, String flow,
                              String scenarioPath, String screen, Boolean fastRun) {
    }

    /**
     * What the token trace calls this walk. The title is the scenario's path
     * when there is one, otherwise the analyst's first line — never the
     * composed instruction, whose first lines are the login.
     */
    private static AiTraces.Meta traceMeta(Instruction body, ActionTape tape) {
        String firstLine = body.text().strip().lines().findFirst().orElse("");
        return new AiTraces.Meta()
                .title(present(body.scenarioPath()) ? body.scenarioPath() : firstLine)
                .app(body.app())
                .product(body.product())
                .flow(body.flow())
                .scenarioPath(body.scenarioPath())
                .cif(body.cif())
                .tapeId(tape.id())
                .secrets(secretsOf(body));
    }

    /** The answer, plus the AI session's id when one is being recorded. */
    private static Map<String, Object> traced(Map<String, ?> answer, String traceId) {
        Map<String, Object> out = new LinkedHashMap<>(answer);
        if (traceId != null) {
            out.put("traceId", traceId);
        }
        return out;
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
    private static ResponseEntity<?> browserClosed(ActionTape tape, AiTraces.Scope trace) {
        trace.fail("Tarayıcı beklenmedik şekilde kapandı");
        return ResponseEntity.status(409).body(traced(Map.of(
                "error", "BROWSER_CLOSED",
                "detail", "Tarayıcı beklenmedik şekilde kapandı",
                "tapeId", tape.id()), trace.id()));
    }

    /**
     * The root cause, PRINTED.
     *
     * This block used to turn an exception straight into a 502 and nothing
     * else, so a failed walk left the UI saying "ClassCastException" and the
     * server log saying nothing at all — the one place the stack trace could
     * have come from threw it away. A failure nobody can locate is paid for
     * twice. The model, the MCP server and the browser are three separate
     * processes and any of them can be the one that broke, so the trace is
     * worth more here than anywhere.
     */
    private static Throwable failure(Exception thrown, String where) {
        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        System.out.println("[uptest] explore(" + where + "): kosu basarisiz — " + named(root));
        thrown.printStackTrace(System.out);
        return root;
    }

    /** "ClassCastException: class X cannot be cast to Y" — what the UI shows. */
    private static String named(Throwable root) {
        return root.getMessage() == null
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static void append(StringBuilder out, String label, String value) {
        if (present(value)) {
            out.append("- ").append(label).append(" = ").append(value.trim()).append('\n');
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * "AI ile çalıştır": the login and the menu in code, then the agent with a
     * short prompt, a narrow toolbox, the page after every action and only the
     * latest page in its history. Measured before this: 52 turns, 1.48 million
     * input tokens for one scenario; see docs/ai-ile-calistir/09-tarihce-ve-dersler.md.
     *
     * Called inside explore()'s try, so the tape is finished and the browser
     * closed on every path out of here, the prelude's included.
     */
    private String runScenario(Instruction body, String composed, AtomicBoolean aborted, ActionTape tape,
                               RunVerdict verdict, AiRunCache.Visit visit) {
        AiRunPrelude.Outcome opened = new AiRunPrelude(tools.getToolCallbacks(), tape, settle).run(
                new AiRunPrelude.Login(body.app(), body.url(), body.cif(), body.userId(),
                        body.password(), body.smsCode()),
                body.mainMenu(), body.screen());
        // A walk that is not logged in cannot do ANY of the scenario, and
        // handing it to the agent is how one run spent 800.000 tokens without
        // ever reaching the application. The login is deterministic — the same
        // boxes, the same buttons, every time — so when code could not do it
        // the environment is wrong (the host is unreachable, the page changed,
        // the user is locked) and a model will not find its way round that. It
        // stops here, with the reason, before a single token is spent.
        if (opened.reached() == AiRunPrelude.Reached.NOTHING && present(body.cif())) {
            throw new IllegalStateException("Ajan başlatılmadı: " + opened.note()
                    + " — ekran açılamadığı için senaryo denenemezdi ve token harcanmadı.");
        }
        PageChrome chrome = PageChrome.forRun(opened, body.mainMenu(), body.screen(), body.text());
        Map<String, String> checkpoints = StepExpectations.checkpoints(body.text());
        String answer = null;
        if (visit.hit()) {
            if (opened.reached() != AiRunPrelude.Reached.ON_SCREEN) {
                // Not the cache's fault: the screen never opened. Fail, keep the cache.
                String why = "Ekran koddan açılamadı, kayıtlı akış oynatılmadı (" + opened.note() + ").";
                verdict.reportResult(List.of(), List.of(why), List.of(), false);
                return verdict.asText();
            }
            FastReplay.Result walk = new FastReplay(tools.getToolCallbacks(), tape, settle)
                    .run(visit.calls, java.time.LocalDate.now(), raw -> summarised(chrome, raw, body.text()));
            visit.log = walk.log();
            visit.stoppedAt = walk.stoppedAt();
            if (walk.complete()) {
                return judge(body, composed, aborted, tape, verdict, walk, chrome, visit.calls);
            }
            // A call that no longer works: the run fails here, in code, and the cache goes.
            String why = walk.stoppedAt() + ". adım tutmadı: " + walk.reason()
                    + " Kayıtlı akış silindi; 'AI ile çalıştır' ile yeniden kaydedin.";
            verdict.reportResult(walk.done(), List.of(why), List.of(), false);
            cache.invalidate(visit, walk.stoppedAt() + ". adım tutmadı: " + walk.reason());
            return verdict.asText();
        }
        if (answer == null) {
            String userTurn = AiRunPrelude.instruction(chrome.shown(opened, body.text(), pageCap), body.text(),
                    composed, body.mainMenu(),
                    body.screen(), BudgetedTool.LARGEST_RESULT)
                    + StepExpectations.checklist(verdict.expected());
            Set<String> allowed = new java.util.HashSet<>(AI_RUN_TOOLS);
            if (opened.reached() == AiRunPrelude.Reached.NOTHING) {
                allowed.add("browser_navigate");
            }
            AiRunPolicy policy = policyFor(chrome);
            System.out.println("[uptest] ai-run: prelude=" + opened.reached()
                    + (opened.note() == null ? "" : " (" + opened.note() + ")")
                    + ", " + allowed.size() + " tarayici araci, talimat " + userTurn.length()
                    + " karakter, token butcesi " + runTokenBudget + ", cache " + visit.state);
            // The SAME client Ekran Keşfi uses — Spring's, configured. The only
            // difference is an advisor that cuts the old pages out of each turn.
            try {
                answer = client.prompt()
                        .system(RUN_SYSTEM + RESULT_RULES)
                        .user(userTurn)
                        .advisors(new CompactingAdvisor())
                        .tools(toolsFor(null, userTurn, new LinkedHashSet<>(), aborted, tape, verdict,
                                policy, allowed, runToolBudget, checkpoints))
                        .call()
                        .content();
            } catch (IllegalStateException unknown) {
                // Spring throws this when the model names a tool it was not given;
                // the walk is over either way, but the analyst gets the reason as a result.
                String message = String.valueOf(unknown.getMessage());
                if (!message.startsWith(UNKNOWN_TOOL) || verdict.isClosed()) {
                    throw unknown;
                }
                String name = message.substring(UNKNOWN_TOOL.length()).strip();
                System.out.println("[uptest] ai-run: ajan olmayan bir araci cagirdi (" + name + ") — kosu durdu");
                verdict.reportResult(List.of(), List.of("Ajan bu koşuda olmayan bir aracı çağırdı (" + name
                        + "); koşu orada durdu. Bu bir uygulama hatası değil, ajan aracı sorunudur."),
                        List.of(), false);
                answer = verdict.asText();
            }
        }
        long used = AiTraces.currentTokens();
        System.out.println("[uptest] ai-run: bitti — " + used + " token, sonuc bildirildi="
                + verdict.isClosed());
        if (opened.note() != null) {
            verdict.warn(opened.reached() == AiRunPrelude.Reached.NOTHING
                    ? "Giriş koddan yapılamadı, ajan kendisi yaptı (" + opened.note() + ")."
                    : "Ekran koddan açılamadı, ajan kendisi açtı (" + opened.note() + ").");
        }
        if (runTokenBudget > 0 && used > runTokenBudget) {
            verdict.warn("Token bütçesi aşıldı: " + String.format(java.util.Locale.forLanguageTag("tr-TR"),
                    "%,d / %,d", used, runTokenBudget) + ".");
        }
        return answer;
    }

    private AiRunPolicy policyFor(PageChrome chrome) {
        AiRunPolicy policy = new AiRunPolicy(raw("browser_snapshot"), settle, runTokenBudget, describeCap, pageCap);
        policy.chrome(chrome);
        policy.busyProbe(raw("browser_evaluate"));
        return policy;
    }

    /**
     * The one model turn of a fast run: the expectations held against the
     * pages kept at the checkpoints, as the agent holds them on a walk. Only
     * report_result is offered — nothing to click.
     */
    private String judge(Instruction body, String composed, AtomicBoolean aborted, ActionTape tape,
                         RunVerdict verdict, FastReplay.Result walk, PageChrome chrome,
                         List<ReplayCalls.ReplayCall> calls) {
        Set<String> attached = new java.util.HashSet<>();
        calls.forEach(call -> attached.add(call.expected()));
        List<String> unmatched = StepExpectations.withExpectation(body.text()).stream()
                .map(StepExpectations.Step::expected).filter(e -> !attached.contains(e)).toList();
        Object[] reportOnly = Arrays.stream(toolsFor(null, composed, new LinkedHashSet<>(), aborted, tape, verdict,
                        null, Set.of(), 3, Map.of()))
                .filter(t -> t instanceof ToolCallback cb && RunVerdict.TOOL.equals(cb.getToolDefinition().name()))
                .toArray();
        String turn = FastReplay.verdictTurn(AiRunPrelude.withoutGoTo(body.text()), walk, unmatched,
                summarised(chrome, walk.lastPage(), body.text()))
                + StepExpectations.checklist(verdict.expected());
        System.out.println("[uptest] ai-cache karar turu: tek AI cagrisi, talimat " + turn.length() + " karakter");
        String answer = client.prompt().system(VERDICT_SYSTEM).user(turn).tools(reportOnly).call().content();
        System.out.println("[uptest] ai-run: bitti — " + AiTraces.currentTokens() + " token (hizli calistir)");
        return answer;
    }

    private String summarised(PageChrome chrome, String raw, String scenario) {
        if (raw == null) {
            return null;
        }
        return chrome.shown(new AiRunPrelude.Outcome(AiRunPrelude.Reached.ON_SCREEN, raw, null), scenario, pageCap)
                .page();
    }

    /** What the "Hızlı çalıştır" button asks: {@code text} is the same text an ai-run would send. */
    public record CacheQuery(String app, String product, String scenarioPath, String text) {
    }

    /** Whether "Hızlı çalıştır" can run this scenario, as it is written now, and if not why. */
    @PostMapping("/cache-status")
    public AiRunCache.Status cacheStatus(@RequestBody CacheQuery query) {
        return cache.status(query.app(), query.product(), query.scenarioPath(), query.text());
    }

    @PostMapping
    public ResponseEntity<?> explore(@RequestBody Instruction body) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        String instruction = compose(body);
        boolean aiRun = "ai-run".equals(body.source());
        boolean fast = aiRun && Boolean.TRUE.equals(body.fastRun());
        // "AI ile çalıştır" never reads the cache (the agent walks, a pass writes it);
        // "Hızlı çalıştır" reads nothing else, and without one no browser is opened.
        AiRunCache.Visit visit = !aiRun ? null : fast
                ? cache.lookup(body.app(), body.product(), body.scenarioPath(), body.text(), body.cif())
                : cache.walked(body.app(), body.product(), body.scenarioPath(), body.text(), body.cif());
        if (fast && !visit.hit()) {
            return ResponseEntity.status(409).body(Map.of("error", "NO_CACHE", "detail", visit.note));
        }
        AtomicBoolean aborted = new AtomicBoolean();
        ActionTape tape = ActionTape.open(tapeDir, "explore", instruction,
                body.url(), body.mainMenu(), secretsOf(body));
        // Every model turn and tool call of this walk lands in one AI session
        // (Token Takibi). Opened after the tape so the session knows its id.
        AiTraces.Scope trace = AiTraces.open(aiRun ? "ai-run" : "explore", traceMeta(body, tape));
        // A saved scenario's run ends in a structured result (RunVerdict);
        // Serbest Gözlem is an observation, not a test, and keeps its prose.
        RunVerdict verdict = aiRun ? new RunVerdict() : null;
        if (verdict != null) {
            verdict.expecting(StepExpectations.all(body.text()));
        }
        try {
            String answer;
            if (aiRun) {
                answer = runScenario(body, instruction, aborted, tape, verdict, visit);
            } else {
                answer = client.prompt()
                        .system(SYSTEM + ANSWER_RULES)
                        .user(instruction)
                        .tools(toolsFor(null, instruction, new LinkedHashSet<>(), aborted, tape, verdict))
                        .call()
                        .content();
            }
            // Belt and braces: should the framework ever turn BrowserClosed
            // into a tool result, the flag still says the walk was cut short.
            if (aborted.get()) {
                return browserClosed(tape, trace);
            }

            // The walk, written back out as steps. A PROPOSAL, never a save:
            // an agent that skipped a step produces a scenario that skips it
            // too, and this run's own tape caught exactly that happening. The
            // analyst reads the lines and decides.
            TapeRenderer.Rendered rendered =
                    TapeRenderer.render(tape.calls(), body.mainMenu(), body.expected());
            // The agent's "done" is held against the tape before anyone sees it.
            ClaimCheck.apply(verdict, body.text(), tape.calls(), rendered.steps());
            // A final Beklenen about a screen the walk had already left fails, in code.
            ExpectationCheck.apply(verdict, tape.calls());
            // After ClaimCheck: only a walk whose verdict survived it is cached.
            cache.settle(visit, verdict, tape, body.text());
            Map<String, Object> reply = new LinkedHashMap<>();
            if (visit != null) {
                reply.put("cache", visit.toJson());
                // The walk told step by step, from the tape: what happened, not what was claimed.
                reply.put("walk", WalkStory.toJson(WalkStory.of(tape.calls())));
            }
            // With a result the tool's own return line ("Sonuç kaydedildi")
            // is what came back as content; the prose every older reader
            // shows is rebuilt from the result instead.
            reply.put("answer", verdict != null && verdict.isClosed() ? verdict.asText() : answer);
            reply.put("tapeId", tape.id());
            reply.put("gherkin", String.join("\n", rendered.steps()));
            reply.put("notes", rendered.notes());
            if (verdict != null && verdict.isClosed()) {
                reply.put("summary", verdict.toJson());
            }
            return ResponseEntity.ok(traced(reply, trace.id()));
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof BudgetedTool.BrowserClosed || aborted.get()) {
                    return browserClosed(tape, trace);
                }
                if (cause.getCause() == cause) {
                    break;
                }
            }
            // The model, the MCP server and the browser are three separate
            // processes; any of them can be the one that failed. Report the
            // root cause rather than a 500 page that names none of them.
            Throwable root = failure(e, "explore");
            trace.fail(named(root));
            return ResponseEntity.status(502).body(traced(Map.of(
                    "error", named(root),
                    "detail", String.valueOf(root.getMessage()),
                    "tapeId", tape.id()), trace.id()));
        } finally {
            // Stamped before the browser goes, and on every path: the tape of
            // a run that died is the one worth reading.
            tape.finish();
            closeBrowser();
            trace.close();
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
        AiTraces.Scope trace = AiTraces.open("catalog", traceMeta(body, tape));

        try {
            CatalogSink sink = new CatalogSink();
            // Emptied by BudgetedTool as each caption is touched; whatever is
            // left when the run ends was skipped.
            Set<String> skipped = new LinkedHashSet<>(required(instruction));

            String spoken = client.prompt()
                    .system(SYSTEM + CATALOG_RULES + schema)
                    .user(instruction)
                    .tools(toolsFor(sink, instruction, skipped, new AtomicBoolean(), tape, null))
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
            return ResponseEntity.ok(traced(Map.of(
                    "catalog", catalog, "problems", problems, "tapeId", tape.id()), trace.id()));
        } catch (Exception e) {
            Throwable root = failure(e, "catalog");
            trace.fail(named(root));
            return ResponseEntity.status(502).body(traced(Map.of(
                    "error", named(root),
                    "detail", String.valueOf(root.getMessage()),
                    "tapeId", tape.id()), trace.id()));
        } finally {
            // Also on the failure paths: a run that died halfway leaves the
            // browser somewhere stranger than a finished one does.
            tape.finish();
            closeBrowser();
            trace.close();
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
