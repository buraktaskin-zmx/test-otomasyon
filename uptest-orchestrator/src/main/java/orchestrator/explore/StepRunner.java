package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Several scenario steps in ONE model turn, each element found BY NAME on the
 * page as it stands when that step runs.
 *
 * <p><b>Why.</b> Every tool call is a whole turn, and a turn pays again for
 * the system prompt, the tool definitions, the chat template and the page.
 * Measured 12.11.2026 on "İptal ekranından Geri ile listeye dönülür": six
 * turns for four steps, a third of every turn being the same fixed parts.
 * Between "pick the row" and "press Fatura İptal Et" the model decides
 * nothing; it pays to be asked again.
 *
 * <p><b>By name, not by reference — the reason this is not 40a2a7f again.</b>
 * The first do_steps (reverted with the page flattening it shipped beside)
 * took a REFERENCE per step, chosen by the model before the batch ran. Tape
 * 2628c4fd shows why that cannot work on these screens: the İşlemler panel is
 * redrawn after every row click (Fatura İptal Et was f3e747, then f3e776, then
 * f3e805), and the cancel screen's "Geri" does not exist at all until "Fatura
 * İptal Et" is pressed. So the model names things as the page PRINTS them,
 * and this class finds each one on the latest page right before acting.
 *
 * <p><b>No less careful than one step per turn:</b>
 * <ol>
 *   <li>every sub-step goes through the WRAPPED tools, so the off-limits
 *       list, the unasked-confirmation guard, the closed browser, the budgets
 *       and the tape treat it exactly as a call from the model;</li>
 *   <li>a name that matches nothing, or more than one element, stops the
 *       batch — nothing is guessed;</li>
 *   <li>a choice is read back (the dropdown shows it, the row's radio is
 *       checked) and a step that did not take stops the batch;</li>
 *   <li>if the screen starts SAYING something it was not saying before the
 *       batch — a dialog, an alert, a "… giriniz." line — the batch stops.</li>
 * </ol>
 * The model gets one line per step and the page as it stands at the end.
 *
 * <p>The tape records the sub-steps under their real tools, so Gherkin and
 * ClaimCheck read a batched walk exactly as a turn-by-turn one; the
 * {@code do_steps} entry itself is silent to both.
 */
final class StepRunner implements ToolCallback {

    static final String TOOL = "do_steps";

    static final int MAX_STEPS = 8;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> CLICKABLE = Set.of(
            "button", "link", "tab", "menuitem", "checkbox", "radio", "option", "treeitem", "switch");

    private static final Set<String> TYPEABLE = Set.of("textbox", "spinbutton", "searchbox");

    private static final String SCHEMA = """
            {"type":"object","properties":{"steps":{"type":"array","maxItems":8,"items":{"type":"object",\
            "properties":{"action":{"type":"string","enum":["click","select","type","row","date","press"]},\
            "name":{"type":"string","description":"As the page prints it: the button/link text, the field label, a cell text of the row, or the key"},\
            "value":{"type":"string","description":"select: the option; type: the text; date: gg/aa/yyyy"}},\
            "required":["action","name"]}}},"required":["steps"]}""";

    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(TOOL)
            .description("Carry out several scenario steps in ONE call, finding each element by its "
                    + "printed name on the page as it is when that step runs. Stops at the first step "
                    + "that cannot be done or when the screen shows a new message.")
            .inputSchema(SCHEMA)
            .build();

    /** One step as the model wrote it. */
    record Step(String action, String name, String value) {
    }

    private Map<String, ToolCallback> tools = Map.of();
    private ActionTape tape;

    /**
     * Compacted name → the expectation the scenario wrote under the step that
     * quotes it ({@link StepExpectations#checkpoints}). A batch stops right
     * after such a step: the page it hands back is where the expectation is
     * checked, and the next step would replace that page.
     */
    private Map<String, String> checkpoints = Map.of();

    StepRunner checkpoints(Map<String, String> checkpoints) {
        this.checkpoints = checkpoints == null ? Map.of() : Map.copyOf(checkpoints);
        return this;
    }

    /**
     * Hands the runner the WRAPPED tools. Two-phase because the runner is
     * itself among the callbacks being wrapped (so the batch is guarded and
     * recorded like any call) and it needs the result of that same wrapping.
     */
    void bind(Object[] wrapped, ActionTape tape) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (Object one : wrapped) {
            if (one instanceof ToolCallback callback && !TOOL.equals(callback.getToolDefinition().name())) {
                byName.put(callback.getToolDefinition().name(), callback);
            }
        }
        this.tools = Map.copyOf(byName);
        this.tape = tape;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DEFINITION;
    }

    @Override
    public String call(String input) {
        return acceptJson(input);
    }

    @Override
    public String call(String input, ToolContext context) {
        return acceptJson(input);
    }

    /**
     * The arguments as a model actually sends them: the list under
     * {@code steps} or {@code actions}, a bare array, an array written out as
     * a string (the shape that cost one finished walk its report_result), or
     * one object meaning a batch of one.
     */
    String acceptJson(String arguments) {
        List<Step> steps = parse(arguments);
        if (steps.isEmpty()) {
            return "do_steps could not read any step. Send: {\"steps\":[{\"action\":\"click\","
                    + "\"name\":\"Fatura İptal Et\"}]}";
        }
        if (steps.size() > MAX_STEPS) {
            // Not truncated: a batch half carried out is worse than one not
            // started, because the model would have to work out which half.
            return "do_steps takes at most " + MAX_STEPS + " steps and you sent " + steps.size()
                    + ". Send the first " + MAX_STEPS + " and the rest in the next call.";
        }
        return run(steps);
    }

    /* ------------------------------------------------------------------ */

    String run(List<Step> steps) {
        if (tools.isEmpty() || tape == null) {
            return "do_steps is not available on this walk; take the steps one at a time.";
        }
        Set<String> before = complaints(AriaSnapshot.of(latestPage()));
        List<String> log = new ArrayList<>();
        String halted = null;
        for (int i = 0; i < steps.size() && halted == null; i++) {
            Step step = steps.get(i);
            String outcome = attempt(step);
            if (!outcome.startsWith("tamam")) {
                halted = outcome;
                log.add(line(i, step, outcome));
                break;
            }
            log.add(line(i, step, outcome));
            Set<String> now = complaints(AriaSnapshot.of(latestPage()));
            now.removeAll(before);
            if (!now.isEmpty()) {
                log.add("   ekran yeni bir şey söylüyor: " + String.join(" | ", now));
                halted = "ekran değişti";
                break;
            }
            String expected = expectationAfter(step);
            if (expected != null && i < steps.size() - 1) {
                log.add("   BEKLENEN KONTROLÜ: senaryo bu adımın altına \"" + expected
                        + "\" yazmış. Aşağıdaki sayfada bunu kontrol et, sonra kalan adımları gönder.");
                halted = "beklenen kontrolü";
            }
        }
        StringBuilder out = new StringBuilder(String.join("\n", log));
        if (halted != null) {
            out.append("\n\nDURDU. Bundan sonraki adımlar YAPILMADI — aşağıdaki sayfaya bakıp devam et.");
        }
        String page = latestPage();
        if (page != null) {
            out.append("\n\n").append(LeanResults.unwrap(page));
        }
        return out.toString();
    }

    /** One scenario step as CodeSteps read it: its number, the action, and the Beklenen under it. */
    record Planned(int number, Step step, String expected) {
    }

    /**
     * The steps carried out by CODE, no model involved (13.11.2026, the
     * analyst's question "can our own Java code do these steps?").
     *
     * <p>Unlike {@link #run}, a step with a Beklenen under it does not halt:
     * the page right after it is kept as a checkpoint, as "Hızlı çalıştır"
     * keeps it, and the walk goes on — the verdict turn holds the expectation
     * against that page. It stops at the first step that does not take, or
     * when the screen starts saying something new while steps remain; then
     * the agent takes over from that step.
     */
    FastReplay.Result runAll(List<Planned> steps) {
        List<String> log = new ArrayList<>();
        List<String> done = new ArrayList<>();
        List<FastReplay.Checkpoint> checkpoints = new ArrayList<>();
        if (tools.isEmpty() || tape == null || steps.isEmpty()) {
            return new FastReplay.Result(log, done, steps.isEmpty() ? 0 : steps.get(0).number(),
                    "kod yürütmesi bu koşuda yok", checkpoints, latestPage());
        }
        Set<String> before = complaints(AriaSnapshot.of(latestPage()));
        for (int i = 0; i < steps.size(); i++) {
            Planned planned = steps.get(i);
            String says = says(planned.step());
            String outcome = attempt(planned.step());
            print(log, planned.number() + ". " + says + " → " + outcome);
            if (!outcome.startsWith("tamam")) {
                return new FastReplay.Result(log, done, planned.number(), outcome, checkpoints, latestPage());
            }
            done.add(planned.number() + ". " + says);
            if (planned.expected() != null) {
                checkpoints.add(new FastReplay.Checkpoint(planned.number(), planned.expected(), latestPage()));
            }
            Set<String> now = complaints(AriaSnapshot.of(latestPage()));
            now.removeAll(before);
            if (!now.isEmpty() && i < steps.size() - 1) {
                String why = "ekran yeni bir şey söylüyor: " + String.join(" | ", now);
                print(log, "   " + why);
                return new FastReplay.Result(log, done, steps.get(i + 1).number(), why, checkpoints, latestPage());
            }
        }
        return new FastReplay.Result(log, done, 0, null, checkpoints, latestPage());
    }

    private static String says(Step step) {
        return step.action() + " \"" + step.name() + "\"" + (blank(step.value()) ? "" : " = \"" + step.value() + "\"");
    }

    private static void print(List<String> log, String line) {
        log.add(line);
        System.out.println("[uptest] ai-run kod " + line);
    }

    /** {@link #perform}, with a failure turned into its reason; a closed browser still ends the walk. */
    String attempt(Step step) {
        try {
            return perform(step);
        } catch (BudgetedTool.BrowserClosed | BudgetedTool.WalkOver over) {
            throw over;
        } catch (RuntimeException failed) {
            return "HATA: " + firstLine(String.valueOf(failed.getMessage()));
        }
    }

    /** The expectation the scenario wrote under the step this one carries out, or null. */
    String expectationAfter(Step step) {
        if (checkpoints.isEmpty()) {
            return null;
        }
        String byName = checkpoints.get(ClaimCheck.compact(step.name()));
        if (byName != null) {
            return byName;
        }
        return blank(step.value()) ? null : checkpoints.get(ClaimCheck.compact(step.value()));
    }

    /** "tamam…" when the step was carried out, otherwise why not. */
    private String perform(Step step) {
        String action = step.action().toLowerCase(Locale.ROOT);
        String name = step.name();
        return switch (action) {
            case "click" -> click(name);
            case "select" -> select(name, step.value());
            case "type" -> type(name, step.value());
            case "row" -> row(name);
            case "date" -> date(name, step.value());
            case "press" -> press(name);
            case "search" -> search(step.value() != null ? step.value() : name);
            default -> "BİLİNMEYEN ADIM '" + step.action() + "': click, select, type, row, date, press";
        };
    }

    private String click(String name) {
        AriaSnapshot page = AriaSnapshot.of(latestPage());
        List<AriaSnapshot.Node> found = byCaption(page, name, CLICKABLE);
        if (found.size() != 1) {
            return notOne(found, name, "tıklanabilir öğe");
        }
        return act("browser_click", Map.of("target", found.get(0).ref(), "element", name));
    }

    private String type(String label, String value) {
        if (blank(value)) {
            return "YAPILMADI: yazılacak değer (value) yok";
        }
        AriaSnapshot page = AriaSnapshot.of(latestPage());
        List<AriaSnapshot.Node> found = byLabel(page, label, TYPEABLE);
        if (found.size() != 1) {
            return notOne(found, label, "yazı kutusu");
        }
        AriaSnapshot.Node kurus = kurusBox(page, found.get(0));
        if (kurus != null && value.contains(",")) {
            return amount(label, found.get(0), kurus, value);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", found.get(0).ref());
        arguments.put("element", label);
        arguments.put("text", value);
        // Masked boxes rebuild their value from key events; one write is
        // painted back over (CLAUDE.md, the fill() lesson).
        arguments.put("slowly", true);
        return act("browser_type", arguments);
    }

    /**
     * The kuruş box of an amount: the unnamed textbox after a "," beside the
     * labelled one, in the same container — the shape every amount on these
     * screens has ({@code textbox "Toplam Tutar": "0"}, {@code generic: ","},
     * {@code textbox: "00"}). Null for an ordinary box.
     */
    static AriaSnapshot.Node kurusBox(AriaSnapshot page, AriaSnapshot.Node box) {
        List<AriaSnapshot.Node> nodes = page.nodes();
        int at = nodes.indexOf(box);
        boolean comma = false;
        for (int i = at + 1; at >= 0 && i < nodes.size() && i <= at + 4; i++) {
            AriaSnapshot.Node next = nodes.get(i);
            if (next.parent() != box.parent()) {
                break;
            }
            if (",".equals(next.caption().replace("\"", "").strip())) {
                comma = true;
            } else if (comma && TYPEABLE.contains(next.role()) && next.name().isBlank()) {
                return next;
            }
        }
        return null;
    }

    /**
     * An amount is two boxes, and the form validates the value it computes
     * from both (CLAUDE.md: "1.250,50" typed into one box leaves the form
     * reading nothing, refused with "Toplam tutar giriniz"). Each box is
     * cleared and typed key by key, then both are read back.
     */
    private String amount(String label, AriaSnapshot.Node lira, AriaSnapshot.Node kurus, String value) {
        String[] parts = value.strip().split(",", 2);
        String whole = parts[0].replaceAll("[^0-9]", "").replaceFirst("^0+(?=\\d)", "");
        String cents = (parts.length > 1 ? parts[1].replaceAll("[^0-9]", "") : "") + "00";
        cents = cents.substring(0, 2);
        if (whole.isEmpty()) {
            return "YAPILMADI: tutar okunamadı: \"" + value + "\"";
        }
        String done = typeInto(lira.ref(), label, whole);
        if (!done.startsWith("tamam")) {
            return done;
        }
        done = typeInto(kurus.ref(), label + " (kuruş)", cents);
        if (!done.startsWith("tamam")) {
            return done;
        }
        AriaSnapshot after = AriaSnapshot.of(latestPage());
        List<AriaSnapshot.Node> boxes = byLabel(after, label, TYPEABLE);
        AriaSnapshot.Node shownKurus = boxes.size() == 1 ? kurusBox(after, boxes.get(0)) : null;
        String shownWhole = boxes.size() == 1 ? digits(boxes.get(0).text()) : "";
        String shownCents = shownKurus == null ? "" : digits(shownKurus.text());
        if (shownWhole.equals(whole) && shownCents.equals(cents)) {
            return "tamam";
        }
        return "YAPILMADI: \"" + label + "\" " + whole + "," + cents + " olmadı, kutular " + shownWhole + ","
                + shownCents + " gösteriyor";
    }

    /** Clears a box and types into it key by key: a masked box rebuilds its value from key events. */
    private String typeInto(String ref, String element, String text) {
        String done = act("browser_click", Map.of("target", ref, "element", element));
        if (!done.startsWith("tamam")) {
            return done;
        }
        done = act("browser_press_key", Map.of("key", "ControlOrMeta+a"));
        if (!done.startsWith("tamam")) {
            return done;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", ref);
        arguments.put("element", element);
        arguments.put("text", text);
        arguments.put("slowly", true);
        return act("browser_type", arguments);
    }

    private static String digits(String text) {
        return (text == null ? "" : text).replaceAll("[^0-9]", "").replaceFirst("^0+(?=\\d)", "");
    }

    /** Types into whatever has the focus — an opened dropdown's search box. */
    private String search(String value) {
        if (blank(value)) {
            return "YAPILMADI: aranacak metin (value) yok";
        }
        return act("browser_press_sequentially", Map.of("text", value));
    }

    private String date(String label, String value) {
        AriaSnapshot page = AriaSnapshot.of(latestPage());
        List<AriaSnapshot.Node> found = byLabel(page, label, TYPEABLE);
        if (found.size() != 1) {
            return notOne(found, label, "tarih kutusu");
        }
        String done = act("set_date", Map.of("target", found.get(0).ref(), "date", value == null ? "" : value));
        String answer = lastResultOf("set_date");
        if (done.startsWith("tamam") && answer != null && !answer.startsWith("Set to")) {
            return "YAPILMADI: " + firstLine(answer);
        }
        return done;
    }

    private String press(String key) {
        return act("browser_press_key", Map.of("key", key));
    }

    /**
     * A dropdown: a native one through select_option, a Select2 by opening it
     * and clicking the option — searching when the option is not in the list
     * — and then READING BACK that the control shows it.
     */
    private String select(String label, String value) {
        if (blank(value)) {
            return "YAPILMADI: seçilecek seçenek (value) yok";
        }
        String plain = LeanResults.unwrap(latestPage());
        AriaSnapshot page = AriaSnapshot.of(plain);
        List<AriaSnapshot.Node> boxes = byLabel(page, label, Set.of("combobox")).stream()
                .filter(box -> !lineOf(plain, box.ref()).contains("[aria-hidden]"))
                .toList();
        if (boxes.size() != 1) {
            return notOne(boxes, label, "açılır liste");
        }
        AriaSnapshot.Node box = boxes.get(0);
        boolean nativeSelect = page.childrenOf(box.ref()).stream().anyMatch(c -> "option".equals(c.role()));
        if (nativeSelect) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("target", box.ref());
            arguments.put("element", label);
            arguments.put("values", List.of(value));
            return act("browser_select_option", arguments);
        }

        String opened = act("browser_click", Map.of("target", box.ref(), "element", label));
        if (!opened.startsWith("tamam")) {
            return opened;
        }
        List<AriaSnapshot.Node> options = byCaption(AriaSnapshot.of(latestPage()), value, Set.of("option"));
        if (options.isEmpty()) {
            // The list may only show what matches what was typed; the open
            // list has put the focus in its search box.
            String typed = act("browser_press_sequentially", Map.of("text", value));
            if (!typed.startsWith("tamam")) {
                return typed;
            }
            options = byCaption(AriaSnapshot.of(latestPage()), value, Set.of("option"));
        }
        if (options.size() != 1) {
            return notOne(options, value, "seçenek");
        }
        String picked = act("browser_click", Map.of("target", options.get(0).ref(), "element", value));
        if (!picked.startsWith("tamam")) {
            return picked;
        }
        // Read back: a click on the right option can still change nothing.
        AriaSnapshot after = AriaSnapshot.of(latestPage());
        boolean shows = byLabel(after, label, Set.of("combobox")).stream()
                .anyMatch(b -> after.childrenOf(b.ref()).stream().anyMatch(c -> same(c.caption(), value))
                        || same(b.caption(), value));
        return shows ? "tamam" : "YAPILMADI: seçenek tıklandı ama \"" + label + "\" hâlâ \"" + value
                + "\" göstermiyor";
    }

    /**
     * A grid row: the first row with a cell reading exactly {@code text}. The
     * ROW is clicked, not its radio — these radios are drawn by their label and
     * the input is hidden, and the row click is what tape 2628c4fd shows
     * working. Read back: the row's radio is checked afterwards.
     */
    private String row(String text) {
        AriaSnapshot page = AriaSnapshot.of(latestPage());
        List<AriaSnapshot.Node> rows = new ArrayList<>();
        for (AriaSnapshot.Node row : page.nodes()) {
            if ("row".equals(row.role()) && descendants(page, row).stream()
                    .anyMatch(n -> "cell".equals(n.role()) && same(n.caption(), text))) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return "YAPILMADI: \"" + text + "\" yazan hücresi olan satır yok";
        }
        AriaSnapshot.Node chosen = rows.get(0);
        String done = act("browser_click", Map.of("target", chosen.ref(), "element", "satır " + text));
        if (!done.startsWith("tamam")) {
            return done;
        }
        String identity = descendants(page, chosen).stream()
                .filter(n -> "cell".equals(n.role()) && !n.caption().isBlank())
                .map(AriaSnapshot.Node::caption).limit(3).reduce((a, b) -> a + " " + b).orElse("");
        String which = rows.size() > 1 ? " (" + rows.size() + " satır eşleşti, ilki: " + identity + ")"
                : " (" + identity + ")";
        AriaSnapshot.Node radio = descendants(page, chosen).stream()
                .filter(n -> "radio".equals(n.role())).findFirst().orElse(null);
        if (radio != null && !lineOf(LeanResults.unwrap(latestPage()), radio.ref()).contains("[checked]")) {
            return "YAPILMADI: satıra tıklandı ama seçilmedi" + which;
        }
        return "tamam" + which;
    }

    /* ------------------------------------------------------------------ */

    /** Calls a wrapped tool and says whether it ran: refused and failed calls show on the tape. */
    private String act(String tool, Map<String, ?> arguments) {
        ToolCallback callback = tools.get(tool);
        if (callback == null) {
            return "YAPILMADI: " + tool + " bu koşuda yok";
        }
        int mark = tape.calls().size();
        String answer = callback.call(json(arguments));
        for (TapeRenderer.Call call : tape.calls().subList(mark, tape.calls().size())) {
            if (tool.equals(call.tool()) && !"OK".equals(call.outcome())) {
                return ("REFUSED".equals(call.outcome()) ? "REDDEDİLDİ: " : "HATA: ") + firstLine(answer);
            }
        }
        if (answer != null && answer.contains("### Error")) {
            return "HATA: " + firstLine(answer.substring(answer.indexOf("### Error") + 9));
        }
        return "tamam";
    }

    /** The latest WHOLE page on the tape: a snapshot narrowed to one element does not count. */
    String latestPage() {
        if (tape == null) {
            return null;
        }
        List<TapeRenderer.Call> calls = tape.calls();
        for (int i = calls.size() - 1; i >= 0; i--) {
            TapeRenderer.Call call = calls.get(i);
            if ("browser_snapshot".equals(call.tool()) && "OK".equals(call.outcome())
                    && (call.args() == null || !call.args().contains("target"))
                    && !AriaSnapshot.of(call.result()).isEmpty()) {
                return call.result();
            }
        }
        return null;
    }

    private String lastResultOf(String tool) {
        List<TapeRenderer.Call> calls = tape.calls();
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (tool.equals(calls.get(i).tool())) {
                return calls.get(i).result();
            }
        }
        return null;
    }

    /** What the screen is SAYING: dialogs, alerts, and the "… giriniz." lines under a field. */
    static Set<String> complaints(AriaSnapshot page) {
        Set<String> out = new LinkedHashSet<>();
        List<AriaSnapshot.Node> nodes = page.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            AriaSnapshot.Node node = nodes.get(i);
            String role = node.role();
            if ("dialog".equals(role) || "alertdialog".equals(role) || "alert".equals(role)) {
                out.add(role + " " + node.caption() + " [" + node.ref() + "]");
                continue;
            }
            String folded = TapeRenderer.fold(node.caption());
            if ((folded.endsWith("giriniz.") || folded.endsWith("seciniz.")) && node.caption().contains(" ")) {
                out.add(node.caption());
            }
        }
        return out;
    }

    private static List<AriaSnapshot.Node> byCaption(AriaSnapshot page, String name, Set<String> roles) {
        List<AriaSnapshot.Node> exact = new ArrayList<>();
        for (AriaSnapshot.Node node : page.nodes()) {
            if (roles.contains(node.role()) && (same(node.caption(), name) || node.ref().equals(name)
                    || same(page.plainName(node.ref()), name))) {
                exact.add(node);
            }
        }
        return exact;
    }

    private static List<AriaSnapshot.Node> byLabel(AriaSnapshot page, String label, Set<String> roles) {
        List<AriaSnapshot.Node> found = new ArrayList<>();
        for (AriaSnapshot.Node node : page.nodes()) {
            if (roles.contains(node.role())
                    && (same(page.labelOf(node.ref()), label) || same(node.name(), label)
                    || node.ref().equals(label))) {
                found.add(node);
            }
        }
        return found;
    }

    private static List<AriaSnapshot.Node> descendants(AriaSnapshot page, AriaSnapshot.Node root) {
        List<AriaSnapshot.Node> nodes = page.nodes();
        List<AriaSnapshot.Node> out = new ArrayList<>();
        int start = nodes.indexOf(root);
        for (int i = start + 1; i < nodes.size() && nodes.get(i).indent() > root.indent(); i++) {
            out.add(nodes.get(i));
        }
        return out;
    }

    private static String notOne(List<AriaSnapshot.Node> found, String name, String what) {
        if (found.isEmpty()) {
            return "YAPILMADI: sayfada \"" + name + "\" adlı " + what + " yok";
        }
        return "YAPILMADI: \"" + name + "\" " + found.size() + " " + what + " ile eşleşti ("
                + found.stream().map(n -> n.role() + " " + n.ref()).limit(4).reduce((a, b) -> a + ", " + b)
                .orElse("") + ") — daha belirgin bir ad ver ya da tek adımda kendin yap";
    }

    private static String lineOf(String plain, String ref) {
        if (plain == null) {
            return "";
        }
        for (String line : plain.split("\n")) {
            if (line.contains("[ref=" + ref + "]")) {
                return line;
            }
        }
        return "";
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && !a.isBlank()
                && TapeRenderer.fold(a).replace(" ", "").equals(TapeRenderer.fold(b).replace(" ", ""));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String line(int index, Step step, String outcome) {
        StringBuilder line = new StringBuilder().append(index + 1).append(". ")
                .append(step.action()).append(" \"").append(step.name()).append('"');
        if (!blank(step.value())) {
            line.append(" = \"").append(step.value()).append('"');
        }
        return line.append(" → ").append(outcome).toString();
    }

    private static String firstLine(String text) {
        String first = text == null ? "" : text.strip().lines().findFirst().orElse("");
        return first.length() > 160 ? first.substring(0, 160) + "…" : first;
    }

    private static String json(Map<String, ?> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (Exception impossible) {
            return "{}";
        }
    }

    /* ------------------------------------------------------------------ */

    private static final List<String> LIST_KEYS = List.of("steps", "actions", "items");

    static List<Step> parse(String arguments) {
        JsonNode root = read(arguments);
        if (root == null) {
            return List.of();
        }
        JsonNode list = root;
        if (root.isObject()) {
            list = null;
            for (String key : LIST_KEYS) {
                JsonNode found = root.get(key);
                if (found != null && !found.isNull()) {
                    list = found.isTextual() ? read(found.asText()) : found;
                    break;
                }
            }
            if (list == null) {
                return root.has("action") ? List.of(step(root)) : List.of();
            }
        }
        if (list.isObject()) {
            return List.of(step(list));
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode element : list) {
            if (element.isObject()) {
                Step step = step(element);
                if (!step.action().isBlank() && !step.name().isBlank()) {
                    steps.add(step);
                }
            }
        }
        return steps;
    }

    private static Step step(JsonNode node) {
        return new Step(first(node, List.of("action", "type", "do")),
                first(node, List.of("name", "label", "element", "field", "target", "ref", "key", "text")),
                first(node, List.of("value", "option", "date", "text")));
    }

    private static String first(JsonNode node, List<String> keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return "";
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception notJson) {
            return null;
        }
    }
}
