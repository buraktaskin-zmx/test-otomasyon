package orchestrator.explore;

import orchestrator.catalog.CatalogModel;
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
     * Deliberately short and concrete. A browsing agent's usual failure is not
     * picking the wrong element — it is never looking: it guesses a selector
     * from memory instead of reading the page. So the first rule is to look.
     */
    private static final String SYSTEM = """
            You are exploring a Turkish corporate internet banking application in
            a real browser, to find out how its screens are built.

            Rules:
            - Snapshot when the PAGE CHANGES, and work from that snapshot's
              references until it changes again. It changes when you navigate,
              when you submit, AND when the application itself rewrites part of
              the form. Never act on a reference from a page that has since
              changed: it may point at an element that no longer exists, and
              the field you meant to fill is simply never found.
              What you do NOT need is a fresh read before every single field.
              Thirteen reads of an unchanged page are what turns a two-minute
              walk into ten.
            - LOOKING FOR ONE ELEMENT? SEARCH, DO NOT SNAPSHOT. There is a tool
              that searches the page for a text and returns just the matching
              nodes with their references. Use it whenever you know what you
              are looking for — a field, a button, a row. A full snapshot of
              one of these pages is enormous, EVERY one you take stays in this
              conversation for the rest of the task, and a run that takes
              twenty of them simply runs out of room and dies half-finished.
              Keep full snapshots for arriving on a screen you have not seen
              and for reading a confirmation table. The snapshot tool also
              takes a target and a depth: snapshotting one table is cheaper
              than the page around it.
            - THE APPLICATION CHANGING THE FORM COUNTS AS A CHANGE. When a
              field you filled makes something happen — another field fills
              itself in, a dependent list loads — wait a few seconds for it to
              settle and then READ THE PAGE AGAIN before you touch the next
              field. This costs one snapshot per reaction, not one per field:
              on a form with a single dependent chain that is one extra read,
              and without it every field after the reaction is being addressed
              from a stale map.
            - AFTER A FAILED ACTION, READ THE PAGE. If a click or a fill comes
              back with an error, do not repeat it and do not invent a
              different way to name the same element. Look first: the element
              moved, or there are two of them, or it is not there at all. A
              guess repeated is still a guess.
            - A CLICK THAT CHANGES NOTHING IS NOT AN ERROR. When you click to
              switch what is displayed — a tab, a filter, one list for another
              — the tool can report success while the page still shows what it
              showed before, because the click landed on something else or on
              nothing at all. So WAIT a couple of seconds for the new content
              to arrive, then read the page and confirm it really switched.
              Waiting first matters: these lists are fetched in the background,
              and reading too early shows you the old one, which looks exactly
              like a click that did nothing.
              This is the worst failure there is: nothing looks wrong, and you
              walk the rest of the flow perfectly on the wrong screen.
            - Fill a form in the order the screen shows it, top to bottom. Skip
              a field you were given no value for, but do not leave the order
              and do not come back to a field you already filled. On these
              screens one field can unlock the next, so the order is functional
              rather than cosmetic — and a run that moves down the page can be
              followed, so when something fails it is obvious where it was.
            - DATES ARE SET WITH set_date, AND ONLY WITH IT. Give it the box
              and the date as gg/aa/yyyy; it opens the calendar and picks year,
              month and day in the order the widget needs. Do NOT type into a
              date box, do NOT drive the calendar yourself, and do NOT touch
              the box afterwards to "make sure" — every one of those has
              produced a wrong date on this application, most recently
              "20/26/2509" in a field that was already correct.
            - AN AMOUNT IS TWO BOXES, NOT ONE. The lira part and the kuruş part
              are separate inputs side by side with "TL" after them, and what
              the form actually reads is a value the page computes from BOTH.
              So put the lira digits in the left box and the two kuruş digits
              in the right one. NEVER type a formatted amount like "1.250,50"
              into a single box: the boxes then look filled while the form
              still reads nothing, you press the submit button, and the screen
              answers "Toplam tutar giriniz" — which is correct, it really is
              empty.
              Send each box in ONE call with its whole number in it ("200",
              then "00"), and switch on the typing tool's slow option — that
              option is what turns the text into real keystrokes, which these
              masked boxes need. Do NOT make a separate call per digit; the
              option already does that for you.
            - DROPDOWNS HERE ARE NOT REAL <select> ELEMENTS. Nearly all of them
              are a custom widget — a span dressed up to look like a dropdown —
              and the tool that picks an option out of a select fails on them
              with "Element is not a <select> element". Drive them the way a
              person does: CLICK the control to open it, read the page, then
              CLICK the option you want in the list that appeared.
              Copy the option's text exactly as the list prints it. Writing it
              from memory produces things like "Taksitli Ödnsin", which matches
              nothing and sends you looking for a fault that is not there.
            - Do exactly what you are asked, one step at a time. Do not invent
              extra steps, do not submit forms you were not asked to submit.
            - THE INSTRUCTION IS THE COMPLETE LIST OF WHAT YOU MAY TOUCH.
              Anything on the screen it does not name — a button, a checkbox,
              an expander, a field you were given no value for — is there to be
              READ and RECORDED, never pressed, filled, ticked or opened.
              "It looked relevant" is not a reason. On these screens an
              unasked-for button does not add detail, it changes what the
              transaction IS, and the run then documents a different
              transaction from the one you were sent to walk.
              The only controls you may operate without being told are the ones
              needed to set a value you WERE given: an option inside a dropdown
              you must set, a day cell in a calendar for a date you must enter,
              a row in a picker you must choose from.
            - This is a bank. Anything that creates, approves or cancels a
              record is irreversible in the test environment too, so never
              press a confirm button unless the instruction says to. When it
              does say to, press it ONCE and wait for the next screen. Do not
              re-read the page first to reassure yourself, and never press it a
              second time because the answer was slow — the record is already
              on its way.
            - Do NOT write or run JavaScript. The tools you were handed are the
              whole toolbox: call one of those by its exact name, never a name
              you expect to exist, and pass exactly the arguments its schema
              asks for. If a step cannot be done with them, say so and stop
              rather than scripting around it.
            - When you have done what was asked, STOP and answer. Do not keep
              exploring, do not retry a tool that failed twice.
            - When you are done, report what you SAW: the labels, the field
              types, what changed after each action. That observation is the
              product, not the clicking.
            """;

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

        if (sink != null) {
            // Ordinary @Tool objects, so they go through the same budget
            // wrapper as the browser tools and need no special case.
            Object[] ours = dates == null ? new Object[]{sink} : new Object[]{sink, dates};
            usable.addAll(List.of(MethodToolCallbackProvider.builder()
                    .toolObjects(ours).build().getToolCallbacks()));
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
                              String password, String smsCode, String mainMenu) {
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
            return ResponseEntity.ok(Map.of("answer", answer, "tapeId", tape.id()));
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
     * The catalog on its own, with any preamble in front of it removed.
     *
     * Told firmly to output YAML and nothing else, the model still writes
     * paragraphs of reasoning first and the catalog after ("...Kataloğu
     * güncelliyorum:" then the YAML). LlmOutputCleaner strips fences and
     * think-blocks but has no way to know where prose ends and a document
     * begins.
     *
     * Every catalog starts with "app:" in column zero (SCHEMA.md makes it
     * required), so that line is the seam. When it is absent, the text is
     * returned untouched and {@link #problems} reports what is wrong with it
     * — better than silently handing back a fragment.
     */
    private static String yamlOnly(String text) {
        if (text == null) {
            return "";
        }
        Matcher start = CATALOG_START.matcher(text);
        return start.find() ? text.substring(start.start()) : text;
    }

    private static final Pattern CATALOG_START = Pattern.compile("(?m)^app:\\s");

    /** The five types SCHEMA.md allows; anything else cannot be driven. */
    private static final Set<String> TYPES =
            Set.of("text", "date", "amount", "dropdown", "account_picker", "checkbox");

    /**
     * What is wrong with the draft, checked mechanically before anyone reads it.
     *
     * A model asked for YAML produces something that LOOKS like a catalog
     * whether or not it is one. These checks cost nothing and catch the
     * failures that otherwise surface minutes later in a browser: a type no
     * step can drive, a dropdown with no options to choose from, a navigation
     * block that cannot reach the screen.
     *
     * Reported, never corrected. Guessing what the model meant would put a
     * second author into the catalog and hide the very mistake worth seeing.
     */
    private static List<String> problems(String yaml, Map<String, String> emptyChoiceReasons) {
        List<String> found = new ArrayList<>();

        CatalogModel model = CatalogModel.parse(yaml).orElse(null);
        if (model == null) {
            // CatalogModel.diagnose exists precisely so this line does not have
            // to guess: it re-reads the draft on the failure path only and
            // names the actual reason, down to SnakeYAML's line and column.
            // "ayristirilamadi ya da 'fields' bolumu yok" covered both a tab in
            // the indentation and an empty field list with one shrug, and the
            // analyst could act on neither.
            found.add("Katalog okunamadi: " + CatalogModel.diagnose(yaml));
            return found;
        }

        // Every field readonly means nobody types anything here: a detail or
        // summary screen, reached in order to be READ. It has no form to fill,
        // no final button and no transaction to complete — so the
        // success_message check further down, which exists to catch a
        // data-entry walk that stopped at the confirmation screen, would be
        // reporting the absence of something this screen never had.
        boolean readOnlyScreen = model.readOnlyScreen();

        for (String label : model.labels()) {
            CatalogModel.CatalogField field = model.field(label).orElseThrow();
            if (!TYPES.contains(field.type())) {
                found.add("\"" + label + "\" tipi '" + field.type()
                        + "' — SCHEMA.md yalnizca " + TYPES + " taniyor.");
            }
            boolean needsChoices = "dropdown".equals(field.type()) || "account_picker".equals(field.type());
            if (needsChoices && field.options().isEmpty()) {
                // Two different failures wearing one sentence until now. The
                // reason is a YAML COMMENT, which SnakeYAML drops, so it cannot
                // be read back out of the draft — the sink carries it instead.
                String reason = emptyChoiceReasons == null ? "" : emptyChoiceReasons.getOrDefault(label, "");
                found.add(reason.isBlank()
                        ? "\"" + label + "\" " + field.type()
                                + " ama hic secenek yazilmamis ve sebep de yazilmamis; ajan bu "
                                + "kontrolu muhtemelen hic ACMADI. Senaryo neyi sececegini bilemez."
                        : "\"" + label + "\" " + field.type() + " ama secenek yok. Ajan acmis ve bos "
                                + "bulmus: \"" + reason + "\". Ekranda gercekten secenek varsa "
                                + "katalogu elle doldurun.");
            }

            // "The box was not there" and "the box was there with an empty
            // list" look identical after a choice, and the agent conflated them
            // the first run it could express either: it put shown_when on
            // "Döviz Cinsi", whose options merely load once "Bayi Kodu" is set.
            //
            // A choice field that carries BOTH a condition and a filled option
            // list is that mistake's shape — the agent saw the list populate,
            // not the field appear. A genuinely revealed field is usually a
            // plain box ("E-Fatura Hash Kodu" is text), so this does not fire
            // on the real case.
            //
            // A question, not a verdict: a revealed dropdown is possible, and
            // only the screen can settle it. But the cost of being wrong is
            // one-sided and large, so it is worth asking every time.
            if (field.conditional() && needsChoices && !field.options().isEmpty()) {
                found.add("\"" + label + "\" hem shown_when tasiyor hem secenekleri dolu. Bu "
                        + "ekranda o sekil genellikle \"alan hep vardi, listesi \""
                        + field.shownWhenField() + "\" secilince yuklendi\" demektir — oysa "
                        + "shown_when \"" + field.shownWhenField() + " = "
                        + field.shownWhenValue() + " degilse alan ekranda YOK\" demek. "
                        + "Yanlissa pahali: uretilen senaryolardan yalnizca o degeri secen "
                        + "alani doldurur, digerleri ona DOKUNMAZ ve zorunlu bir alan bos "
                        + "kalir. Alan gercekten kayboluyorsa birakin; yalnizca listesi gec "
                        + "geliyorsa shown_when satirlarini silin.");
            }
        }

        // Cheap text checks: CatalogModel deliberately reads only fields, and
        // teaching it the navigation block just for this would be more schema
        // to keep in step.
        if (!yaml.contains("main_menu")) {
            found.add("navigation.main_menu yok — ekrana nasil gidilecegi yazili degil.");
        }
        if (!yaml.contains("sub_menu")) {
            found.add("navigation.sub_menu yok.");
        }
        if (yaml.contains("entry_button") && !yaml.contains("entry_row")) {
            found.add("entry_button var ama entry_row yok. Ekran bir izgaradan satir "
                    + "secilerek aciliyorsa bu satir eksik ve senaryo yanlis kaydi acar.");
        }

        // The two cannot honestly disagree: a success_message can only be read
        // on the result screen, which is reached THROUGH the confirmation
        // screen. So the agent saw that screen and did not record it, and the
        // omission is invisible in the YAML itself — nothing looks missing.
        if (yaml.contains("success_message") && !yaml.contains("verifiable")) {
            found.add("success_message var ama hicbir alan verifiable degil. Sonuc ekranina "
                    + "ulasilmis, demek ki onay ekranindan gecilmis, ama note_confirmation "
                    + "cagrilmamis. Bu katalogtan uretilen senaryo formu doldurur, Onay'a basar "
                    + "ve yalnizca banner'i dogrular: sessizce bos kalan bir alan yine yesil gecer.");
        }

        // The mirror of the check above, and the more common direction: it read
        // the confirmation screen and stopped there. Nothing in the YAML looks
        // wrong — the fields are all present and flagged — but the flow was
        // never finished, so the one text that proves the transaction completed
        // was never seen. A scenario built from this fills the form, presses
        // Devam, verifies the summary, and never presses the final button at
        // all.
        if (!readOnlyScreen && yaml.contains("verifiable") && !yaml.contains("success_message")) {
            found.add("Alanlar verifiable ama success_message yok. Ajan onay ekranini okudu ve "
                    + "orada durdu: son butona basmadi ya da sonuc ekrani gelmedi. Bu katalogtan "
                    + "uretilen senaryo islemin tamamlandigini KANITLAMAZ, yalnizca onay ekranini "
                    + "dogrular. Akisi sonuna kadar bir daha kosun ya da metni elle ekleyin.");
        }

        // A handful of verifiable fields out of many is the shape of a summary
        // table read only partway down — the agent's known weakness. It can
        // also be perfectly correct, because some screens really do echo a few
        // values, so this is a question put to the reviewer, not a verdict.
        long fields = yaml.lines().filter(line -> line.startsWith("  - label:")).count();
        long verifiable = yaml.lines().filter(line -> line.trim().equals("verifiable: true")).count();
        if (yaml.contains("success_message") && verifiable > 0 && verifiable * 2 < fields) {
            found.add(fields + " alandan yalnizca " + verifiable + " tanesi verifiable. Onay ekrani "
                    + "gercekten bu kadarini gosteriyorsa sorun yok; aksi halde ajan ozet tablosunu "
                    + "yarida birakmis ve senaryo geri kalan alanlarin dogru tasindigini hic "
                    + "kontrol etmeyecek. Ekrani bir gorup eksikleri elle ekleyin.");
        }
        return found;
    }

    /**
     * Added on top of SYSTEM for the free-text endpoint, which both Ekran
     * Keşfi's Serbest Gözlem and a scenario's "AI ile çalıştır" call. The
     * answer is read by an analyst checking one scenario, so it has to be
     * about that scenario and scannable, not a narrative of the walk.
     */
    private static final String ANSWER_RULES = """

            HOW TO WRITE YOUR FINAL ANSWER
            - Write it in TURKISH.
            - Write it as a numbered list, one item per step of the instruction:
              what you did, and what the screen showed. Short sentences.
            - Answer ONLY what the instruction asked. Do not describe menus,
              pages or fields the instruction did not mention.
            - If the instruction states an expected result, end with one line
              "Sonuç: BEKLENDİĞİ GİBİ" or "Sonuç: BEKLENDİĞİ GİBİ DEĞİL",
              followed by the reason in one sentence.
            - If you could not finish, say at which step and why.
            """;

    /**
     * Added on top of SYSTEM for the catalog endpoint. The uncertainty rule is
     * the important one: a draft that marks what it guessed is reviewable, a
     * draft that states everything with equal confidence is not.
     */
    private static final String CATALOG_RULES = """

            OUTPUT: you do not write the catalog as text. You build it with
            four tools as you go.

            1. note_field — call it for EVERY field you can FILL IN, right
               after you examine it, before moving on. One call per field. Do
               not save them up for the end: what you record now is what the
               catalog will say, and a run that ends early still keeps what
               you noted.
            2. note_display — call it for every value the screen only SHOWS
               you: a row of a detail screen, a summary line, a field the
               application filled in itself. Give the label and the value
               printed beside it as two separate arguments. Some screens have
               nothing to fill in at all — a detail screen reached to be read.
               On those, note_field has nothing to record and these calls ARE
               the catalog; without them the walk produces an empty one.
            3. note_confirmation — call it ONCE when a screen shows your values
               back to you, BEFORE you press the final confirm. See the rule
               below; a catalog without this call is half a catalog.
            4. submit_catalog — call it ONCE at the very end, with how the
               screen is reached and what the flow presses. If you passed a
               confirmation screen and did not call note_confirmation, its
               labels go here in "confirmedLabels" — do not let them fall on
               the floor. It closes the task; call no tool after it.

            The schema below explains what each piece means; the two tools take
            the same information as arguments.

            - Copy every label, option and button caption EXACTLY as the page
              shows it, Turkish characters included. A single wrong letter
              makes the catalog useless.
            - Where you are not certain about a field, still give your best
              guess but fill its "uncertain" argument with why. A draft that
              marks its guesses can be reviewed; one that hides them cannot.
            - A FIELD THAT APPEARED LATER IS CONDITIONAL — AN EMPTY LIST IS
              NOT. These two look alike after a choice and mean opposite
              things, so decide which one you actually saw:
              * THE BOX ITSELF WAS NOT THERE. Its label was absent from the
                page, and appeared only once another field took a particular
                value. That is conditional: note it with shownWhenField and
                shownWhenValue naming that field and that value.
              * THE BOX WAS THERE ALL ALONG AND ITS LIST WAS EMPTY until
                another field was set. That is an ordinary dependent dropdown,
                and on this application most of them are. Leave both shownWhen
                arguments "" and say what you saw in `uncertain` instead.
              The question to ask yourself is simply: was the LABEL missing
              from the page, or was the label there with nothing inside it?
              Getting this wrong is expensive in one direction. A shownWhen
              that should not be there makes every scenario that does not pick
              that exact trigger value SKIP the field completely — so a
              required field is left empty in all of them and every one of
              those runs is refused by the form.
              For a field that was there from the start, pass "" for both.
            - HOW TO DECIDE "type" IN THIS APPLICATION. These are the same
              rules the DOM scanner applies, rewritten as things you can see:
              * amount — TWO number boxes side by side with a currency word
                ("TL") after them: the lira part and the kuruş part. On a
                read-only screen it is one value ending in "TL". Money is
                NEVER "text": the amount steps normalise "1.250,50" and the
                text steps compare it letter by letter.
              * date — a box holding gg/aa/yyyy, usually with a calendar icon,
                often showing an empty mask like __/__/____ .
              * account_picker — a dropdown whose options carry long runs of
                digits (an account number or IBAN). A dropdown of words is
                never an account picker.
              * dropdown — anything else you open and choose from. Write the
                option texts EXACTLY, and leave out the "Seçiniz" placeholder.
                TO LEARN THE OPTIONS YOU HAVE TO OPEN IT: a closed dropdown on
                this application contains nothing, because the list is built
                when you click it. Open it, read the list, and close it again
                if you were not asked to choose. note_field sends you back for
                a dropdown with no options unless you say why in `uncertain` —
                "opened it, empty until Bayi Kodu is chosen" is a real answer,
                silence is not.
              * checkbox — a box the user ticks, captioned on these screens
                with a whole sentence ("Garanti tutarı girmek istiyorum.").
                Record every one you find. Never call it "text": nothing can
                be typed into it.
              * text — what is left.
              Where the screen does not let you tell these apart, give your
              guess and say so in that field's "uncertain" argument.
            - success_message: if the instruction took you through the final
              confirm button, you WILL end up on a result screen — read the
              banner there and pass it verbatim. It is the one thing in the
              catalog that cannot be learned by looking at the form, and
              without it every scenario ends without proving the transaction
              completed. If the page goes blank, check the browser tabs: the
              result may have opened in a new one.
              Never invent it. If you did not reach that screen, leave it out.
            - THE CONFIRMATION SCREEN IS A TOOL CALL OF ITS OWN. A submit
              usually lands on a screen that shows your values back to you
              before the final confirm. STOP there, read it whole — every row
              of every summary table, not just the first few — and call
              note_confirmation once with every label printed on it.
              This is what makes fields "verifiable", and it matters more than
              anything else you record: a scenario built from a catalog without
              it fills the form, presses the confirm button and checks NOTHING
              but the banner. Then a field that silently stayed empty still
              passes. Fields the application filled in ITSELF are the most
              valuable of all — verifying those is the only way anyone ever
              tests that the bank looked the value up correctly.
              * If you forget, or realise only later, put the same labels in
                submit_catalog's "confirmedLabels". Losing them is the one
                mistake that cannot be repaired afterwards — the screen is
                gone by then.
              * If you were not instructed to submit you never reach that
                screen: do not call it, and do not submit just to find out.
              * A read-only detail screen IS a summary screen, but there is
                nothing on it you filled in, so note_confirmation has nothing
                to flag. Record its rows with note_display instead — label and
                printed value, one call each.
            - "navigation" must describe how to REACH the screen, step by step,
              INCLUDING the parts that were not obvious:
              * if you had to switch a tab or a filter before the list showed
                what you wanted, record it as entry_tab. A list that opens on
                one filter and holds the records under another is the quietest
                trap in this application: a scenario that skips the switch
                picks the right row of the wrong list and passes every step.
              * if you had to pick a row from a grid before a link became
                available, record it as entry_row — a scenario that skips it
                opens the wrong record or nothing at all.
              * if the button you pressed did not open the form but a FORK —
                a small group of choices deciding WHICH screen comes next —
                record the option you took as entry_choice. The other branch
                is a different screen with different fields, so this is part
                of the route, not a field of the form. Without it the form
                never opens at all and every field times out in turn.
            - Catalogue ONLY the screen you were asked to examine. Do not
              wander into other screens to make the catalog look complete.

            === CATALOG SCHEMA ===
            """;

    /**
     * The closing instruction, appended to the ANALYST'S text rather than kept
     * in the system prompt — and it carries no new information at all.
     *
     * Everything in it is already said above, in CATALOG_RULES, twice: once as
     * "call submit_catalog with how the screen is reached" and once as the
     * navigation rule that names entry_tab and entry_row outright. Saying it a
     * third time in the same place would be the mistake this project keeps
     * relearning. What changes here is POSITION: this is the last thing the
     * model reads before it starts, where "do this at the end" belongs. Cost,
     * four lines.
     *
     * It is appended in the /catalog handler and NOT inside compose(), because
     * /explore builds its tools with no sink — there is no submit_catalog on
     * that path, and telling a model to call a tool it was not given is how a
     * run ends in an invented tool name.
     *
     * NO REAL CAPTION MAY APPEAR HERE. The composed instruction is the very
     * text BudgetedTool searches in unmentioned(), banned() and required().
     * Writing "Onay" into it would quietly licence pressing an Onay button in
     * a flow whose analyst never mentioned one — the guard would see the word
     * and let it through. Hence "son onay düğmesi" and "değerlerini tekrar
     * gösteren ekran" instead of naming anything.
     */
    private static final String CLOSING = """


            Bitirmek için submit_catalog'u çağır ve HER argümanını doldur:
            ekrana nasıl gittiğini (menü, alt menü, varsa geçtiğin sekme, varsa
            ızgaradan seçtiğin satır, varsa formu açan bağlantı), bastığın
            düğmeleri, son ekranda okuduğun metni, ve değerlerini tekrar
            gösteren ekranda gördüğün tüm etiketleri. Bir şey gerçekten yoksa
            "" geç — atlamak yok.
            """;

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
}
