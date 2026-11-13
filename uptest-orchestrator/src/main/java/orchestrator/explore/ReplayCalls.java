package orchestrator.explore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import orchestrator.explore.TapeRenderer.Call;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A passed walk written down as the TOOL CALLS that made it, each aimed at a
 * target that does not depend on the page — so "Hızlı çalıştır" can send them
 * again in order without reading the page at all.
 *
 * <p><b>Where the target comes from.</b> The agent acts on references
 * ({@code e42}) that belong to one page load. But for every action the MCP
 * server echoes the Playwright line it actually ran —
 * {@code await page.getByRole('link', { name: 'Fatura İptal Et' }).click();} —
 * and the tape keeps it. That locator is turned into the selector string
 * Playwright itself serialises it to ({@code internal:role=link[name="Fatura
 * İptal Et"i]}), which the MCP tools accept as {@code target} just as they
 * accept the CSS {@link AiRunPrelude} sends.
 *
 * <p>A call whose locator cannot be written down (an {@code aria-ref},
 * a regular expression, a method this class does not know) makes the walk
 * uncacheable, with the reason: a replay that skips a step is a different
 * scenario, and one that guesses a target is not a replay.
 */
final class ReplayCalls {

    /**
     * One call to send again.
     *
     * @param tool      the MCP tool (or {@code set_date})
     * @param target    the page-independent selector, or null for a call with no target
     * @param args      the call's other arguments, as JSON, without {@code target}
     * @param dayOffset set_date only: days from the recording day to the date set
     * @param names     what the call names (accessible name, label, value), for logs and alignment
     * @param says      one readable line for the console
     * @param expected  the expectation written under the scenario step this call carries out, or null
     */
    record ReplayCall(String tool, String target, String args, Integer dayOffset, List<String> names,
                      String says, String expected) {

        /** The arguments to send today. */
        String argsFor(LocalDate today) {
            ObjectNode node = object(args);
            if (target != null) {
                node.put("target", target);
            }
            if ("set_date".equals(tool) && dayOffset != null) {
                node.put("date", today.plusDays(dayOffset).format(DAY));
            }
            return node.toString();
        }

        String dateFor(LocalDate today) {
            return dayOffset != null ? today.plusDays(dayOffset).format(DAY) : text(object(args), "date");
        }

        ReplayCall expecting(String expectation) {
            return new ReplayCall(tool, target, args, dayOffset, names, says, expectation);
        }
    }

    /** The calls, or why there are none. */
    record Extracted(List<ReplayCall> calls, String refusal) {
        boolean usable() {
            return refusal == null && !calls.isEmpty();
        }
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The actions a walk is made of; everything else on a tape is looking, or the code's own. */
    static final Set<String> ACTIONS = Set.of(
            "browser_click", "browser_type", "browser_select_option", "browser_press_key",
            "browser_press_sequentially", "set_date");

    /** Calls that change the page but cannot be sent again safely or by target. */
    private static final Set<String> NOT_REPLAYABLE = Set.of(
            "browser_handle_dialog", "browser_fill_form", "browser_navigate", "browser_drag",
            "browser_file_upload", "browser_hover", "browser_navigate_back");

    /** A snapshot reference: e42, or f1e719 inside a frame. Anything else in target is a selector already. */
    private static final Pattern REF = Pattern.compile("^(?:f\\d+)?e\\d+$");

    private static final String CODE_HEADING = "### Ran Playwright code";

    private ReplayCalls() {
    }

    /* ------------------------------------------------------------------ */

    /**
     * @param calls    the whole tape of a passed walk
     * @param recorded the day it ran, for date offsets
     */
    static Extracted fromWalk(List<Call> calls, LocalDate recorded) {
        List<ReplayCall> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < calls.size(); i++) {
            if (AiRunPrelude.MENU_TOOL.equals(calls.get(i).tool())) {
                start = i + 1;
            }
        }
        Map<String, String> refSelector = new HashMap<>();
        Map<String, String> selectorRef = new HashMap<>();
        for (int i = start; i < calls.size(); i++) {
            Call call = calls.get(i);
            // A tape recorded before BudgetedTool.reportsError marks our own
            // tools' "### Error" answers OK; those are failed calls too.
            if (!"OK".equals(call.outcome()) || BudgetedTool.reportsError(call.result())) {
                continue;
            }
            if (NOT_REPLAYABLE.contains(call.tool())) {
                return refuse(call, call.tool() + " tekrar gönderilemez");
            }
            if (!ACTIONS.contains(call.tool())) {
                continue;
            }
            if ("set_date".equals(call.tool()) && !DateTool.wasSet(call.result())) {
                continue;
            }
            ObjectNode args = object(call.args());
            String target = text(args, "target");
            args.remove("target");
            args.remove("ref");
            String selector = null;
            List<String> names = new ArrayList<>();
            if (!target.isEmpty()) {
                if (REF.matcher(target).matches()) {
                    Selector parsed = Selector.of(codeLine(call.result()));
                    if (parsed.selector() == null) {
                        return refuse(call, "hedefi sayfadan bağımsız yazılamadı (" + parsed.why() + ")");
                    }
                    selector = parsed.selector();
                    names.addAll(parsed.names());
                    String known = refSelector.get(target);
                    String first = selectorRef.get(selector);
                    if (known != null) {
                        selector = known;
                    } else if (first != null && !first.equals(target) && isKurusOf(calls, i, first, target)) {
                        selector = selector + KURUS;
                    }
                    selectorRef.putIfAbsent(parsed.selector(), target);
                    refSelector.put(target, selector);
                } else {
                    selector = target;
                }
            }
            for (String key : List.of("text", "key")) {
                if (!text(args, key).isEmpty()) {
                    names.add(text(args, key));
                }
            }
            JsonNode values = args.get("values");
            if (values != null && values.isArray()) {
                values.forEach(v -> names.add(v.asText()));
            }
            Integer offset = null;
            if ("set_date".equals(call.tool())) {
                offset = offset(text(args, "date"), recorded);
            }
            String element = text(args, "element");
            if (!element.isEmpty()) {
                names.add(element);
            }
            out.add(new ReplayCall(call.tool(), selector, args.toString(), offset, List.copyOf(names),
                    says(call.tool(), names, text(args, "date")), null));
        }
        if (out.isEmpty()) {
            return new Extracted(List.of(), "yürüyüşte tekrar gönderilecek bir eylem yok");
        }
        return new Extracted(out, null);
    }

    /**
     * Each expectation the scenario wrote under a step, put on the call that
     * carries that step out: the first one, from where the previous match left
     * off, that names something the step quotes.
     *
     * @return the expectations no call could be matched to; the verdict turn
     *         holds them against the last page
     */
    static List<String> align(List<ReplayCall> calls, String scenario) {
        List<String> unmatched = new ArrayList<>();
        int from = 0;
        for (StepExpectations.Step wanted : StepExpectations.withExpectation(scenario)) {
            List<String> quoted = wanted.quoted().stream().map(ClaimCheck::compact).toList();
            int hit = -1;
            for (int k = from; k < calls.size() && hit < 0; k++) {
                if (calls.get(k).names().stream().map(ClaimCheck::compact).anyMatch(quoted::contains)) {
                    hit = k;
                }
            }
            if (hit < 0) {
                unmatched.add(wanted.expected());
                continue;
            }
            // The LAST call of a run of calls on the same step: a dropdown is opened,
            // then its option clicked, and the expectation is about after the option.
            int last = hit;
            while (last + 1 < calls.size() && calls.get(last + 1).names().stream().map(ClaimCheck::compact)
                    .anyMatch(quoted::contains)) {
                last++;
            }
            calls.set(last, calls.get(last).expecting(wanted.expected()));
            from = last + 1;
        }
        return unmatched;
    }

    static String toJson(List<ReplayCall> calls) {
        try {
            return JSON.writeValueAsString(calls);
        } catch (Exception impossible) {
            return "[]";
        }
    }

    /** Empty for anything unreadable — including a row written by the earlier, name-based cache. */
    static List<ReplayCall> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ReplayCall> calls = JSON.readValue(json, new TypeReference<List<ReplayCall>>() { });
            return calls.stream().allMatch(c -> c.tool() != null && c.says() != null) ? calls : List.of();
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * A Playwright locator chain as written in JS, turned into the selector
     * string Playwright serialises it to (its {@code locatorUtils}): the same
     * element, without a reference.
     */
    record Selector(String selector, List<String> names, String why) {

        private static final Pattern STEP = Pattern.compile("\\.([A-Za-z]+)\\(");

        private static final Set<String> FINAL = Set.of(
                "click", "dblclick", "fill", "pressSequentially", "selectOption", "press", "check",
                "uncheck", "type", "setInputFiles", "hover", "focus", "clear");

        static Selector of(String line) {
            if (line == null || line.isBlank()) {
                return fail("MCP çalıştırdığı satırı döndürmemiş");
            }
            int at = line.indexOf("page.");
            if (at < 0) {
                return fail("satırda page. yok");
            }
            String rest = line.substring(at + 4);
            List<String> parts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            int pos = 0;
            while (pos < rest.length()) {
                Matcher step = STEP.matcher(rest);
                if (!step.find(pos) || step.start() != pos) {
                    break;
                }
                String method = step.group(1);
                int open = step.end();
                int close = closing(rest, open);
                if (close < 0) {
                    return fail("satır okunamadı");
                }
                String inside = rest.substring(open, close);
                pos = close + 1;
                if (FINAL.contains(method)) {
                    break;
                }
                String part = part(method, inside, names);
                if (part == null) {
                    return fail(method + "(" + shorten(inside) + ")");
                }
                parts.add(part);
            }
            if (parts.isEmpty()) {
                return fail("satırda bulucu yok");
            }
            return new Selector(String.join(" >> ", parts), List.copyOf(names), null);
        }

        private static Selector fail(String why) {
            return new Selector(null, List.of(), why);
        }

        private static String part(String method, String inside, List<String> names) {
            List<String> strings = strings(inside);
            boolean exact = inside.matches("(?s).*\\bexact:\\s*true.*");
            switch (method) {
                case "getByRole" -> {
                    if (strings.isEmpty()) {
                        return null;
                    }
                    StringBuilder out = new StringBuilder("internal:role=").append(strings.get(0));
                    for (String flag : List.of("checked", "disabled", "selected", "expanded", "pressed",
                            "includeHidden")) {
                        Matcher value = Pattern.compile("\\b" + flag + ":\\s*(true|false)").matcher(inside);
                        if (value.find()) {
                            out.append('[').append(flag.equals("includeHidden") ? "include-hidden" : flag)
                                    .append('=').append(value.group(1)).append(']');
                        }
                    }
                    Matcher level = Pattern.compile("\\blevel:\\s*(\\d+)").matcher(inside);
                    if (level.find()) {
                        out.append("[level=").append(level.group(1)).append(']');
                    }
                    Matcher name = Pattern.compile("\\bname:\\s*(['\"`])").matcher(inside);
                    if (name.find()) {
                        if (strings.size() < 2) {
                            return null;
                        }
                        out.append("[name=").append(attribute(strings.get(1), exact)).append(']');
                        names.add(strings.get(1));
                    } else if (inside.matches("(?s).*\\bname:\\s*/.*")) {
                        return null;
                    }
                    return out.toString();
                }
                case "getByText", "getByLabel" -> {
                    if (strings.isEmpty() || inside.strip().startsWith("/")) {
                        return null;
                    }
                    names.add(strings.get(0));
                    return ("getByText".equals(method) ? "internal:text=" : "internal:label=")
                            + text(strings.get(0), exact);
                }
                case "getByPlaceholder", "getByTitle", "getByAltText" -> {
                    if (strings.isEmpty() || inside.strip().startsWith("/")) {
                        return null;
                    }
                    names.add(strings.get(0));
                    String attr = switch (method) {
                        case "getByPlaceholder" -> "placeholder";
                        case "getByTitle" -> "title";
                        default -> "alt";
                    };
                    return "internal:attr=[" + attr + "=" + attribute(strings.get(0), exact) + "]";
                }
                case "getByTestId" -> {
                    return strings.isEmpty() ? null
                            : "internal:testid=[data-testid=" + attribute(strings.get(0), true) + "]";
                }
                case "locator" -> {
                    if (strings.isEmpty() || strings.get(0).startsWith("aria-ref=")) {
                        return null;
                    }
                    return strings.get(0);
                }
                case "first" -> {
                    return "nth=0";
                }
                case "last" -> {
                    return "nth=-1";
                }
                case "nth" -> {
                    String n = inside.strip();
                    return n.matches("-?\\d+") ? "nth=" + n : null;
                }
                case "filter" -> {
                    Matcher has = Pattern.compile("\\bhasText:\\s*(['\"`])").matcher(inside);
                    if (!has.find() || strings.isEmpty()) {
                        return null;
                    }
                    names.add(strings.get(0));
                    return "internal:has-text=" + text(strings.get(0), false);
                }
                default -> {
                    return null;
                }
            }
        }

        /** Playwright's escapeForAttributeSelector: {@code "value"s} exact, {@code "value"i} not. */
        static String attribute(String value, boolean exact) {
            return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"' + (exact ? "s" : "i");
        }

        /** Playwright's escapeForTextSelector: JSON-quoted, then s / i. */
        static String text(String value, boolean exact) {
            try {
                return JSON.writeValueAsString(value) + (exact ? "s" : "i");
            } catch (Exception impossible) {
                return '"' + value + '"' + (exact ? "s" : "i");
            }
        }

        /** Every string literal in a call's arguments, unescaped, in order. */
        static List<String> strings(String inside) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < inside.length(); i++) {
                char quote = inside.charAt(i);
                if (quote != '\'' && quote != '"' && quote != '`') {
                    continue;
                }
                StringBuilder value = new StringBuilder();
                int k = i + 1;
                for (; k < inside.length() && inside.charAt(k) != quote; k++) {
                    char c = inside.charAt(k);
                    if (c == '\\' && k + 1 < inside.length()) {
                        char next = inside.charAt(++k);
                        switch (next) {
                            case 'n' -> value.append('\n');
                            case 't' -> value.append('\t');
                            case 'u' -> {
                                if (k + 4 < inside.length()) {
                                    value.append((char) Integer.parseInt(inside.substring(k + 1, k + 5), 16));
                                    k += 4;
                                }
                            }
                            default -> value.append(next);
                        }
                    } else {
                        value.append(c);
                    }
                }
                out.add(value.toString());
                i = k;
            }
            return out;
        }

        /** The index of the ')' closing the '(' before {@code open}, skipping strings and nesting. */
        private static int closing(String text, int open) {
            int depth = 1;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'' || c == '"' || c == '`') {
                    for (i++; i < text.length() && text.charAt(i) != c; i++) {
                        if (text.charAt(i) == '\\') {
                            i++;
                        }
                    }
                } else if (c == '(' || c == '{' || c == '[') {
                    depth++;
                } else if (c == ')' || c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return c == ')' ? i : -1;
                    }
                }
            }
            return -1;
        }

        private static String shorten(String text) {
            return text.length() > 40 ? text.substring(0, 40) + "…" : text;
        }
    }

    /**
     * The kuruş box, relative to the lira box's selector: the first visible
     * input after it — the next box in the form, as Tab would reach it.
     *
     * <p>Measured 13.11.2026 (cache from tape 705c39be): the lira box e722
     * and the kuruş box e724 came back from MCP with the SAME line,
     * {@code getByRole('textbox', { name: 'Toplam Tutar' })} — the kuruş box
     * has no name of its own — so the cache stored one selector for both,
     * and "Hızlı çalıştır" typed "50" into the lira box: {@code 100.050},
     * {@code 00}.
     */
    static final String KURUS = " >> xpath=following::input[not(@type=\"hidden\")] >> visible=true >> nth=0";

    /**
     * Whether {@code second} is the kuruş box of {@code first} on the page
     * the walk was looking at — read off the tape's latest whole page before
     * call {@code at}, with the same rule do_steps uses (StepRunner.kurusBox).
     * Only then is a shared selector rewritten; any other two elements that
     * share one ("Seçiniz" on two dropdowns, a box re-rendered under a new
     * reference) are left as they are.
     */
    static boolean isKurusOf(List<Call> calls, int at, String first, String second) {
        for (int i = at - 1; i >= 0; i--) {
            Call call = calls.get(i);
            if (!"browser_snapshot".equals(call.tool()) || !"OK".equals(call.outcome()) || call.result() == null) {
                continue;
            }
            AriaSnapshot page = AriaSnapshot.of(call.result());
            AriaSnapshot.Node lira = page.nodes().stream().filter(n -> n.ref().equals(first)).findFirst()
                    .orElse(null);
            if (lira == null) {
                continue;
            }
            AriaSnapshot.Node kurus = StepRunner.kurusBox(page, lira);
            return kurus != null && kurus.ref().equals(second);
        }
        return false;
    }

    /* ------------------------------------------------------------------ */

    /** The first {@code await page…} line of the code block MCP echoed. */
    static String codeLine(String result) {
        if (result == null) {
            return "";
        }
        // plain(), not unwrap(): set_date's answer sits in JSON quotes on the
        // tape, its line breaks escaped, so unwrap() left one long line.
        String plain = BudgetedTool.plain(result);
        int start = plain.indexOf(CODE_HEADING);
        if (start < 0) {
            return "";
        }
        for (String line : plain.substring(start + CODE_HEADING.length()).split("\\R")) {
            if (line.startsWith("###")) {
                break;
            }
            if (line.contains("page.")) {
                return line.strip();
            }
        }
        return "";
    }

    private static String says(String tool, List<String> names, String date) {
        String verb = switch (tool) {
            case "browser_click" -> "tıkla";
            case "browser_type", "browser_press_sequentially" -> "yaz";
            case "browser_select_option" -> "seç";
            case "browser_press_key" -> "tuş";
            case "set_date" -> "tarih";
            default -> tool;
        };
        StringBuilder out = new StringBuilder(verb);
        names.stream().distinct().forEach(name -> out.append(" \"").append(name).append('"'));
        if (!date.isEmpty()) {
            out.append(" = ").append(date);
        }
        return out.toString();
    }

    private static Integer offset(String date, LocalDate recorded) {
        try {
            return (int) ChronoUnit.DAYS.between(recorded, LocalDate.parse(date.strip(), DAY));
        } catch (DateTimeParseException | NullPointerException notADay) {
            return null;
        }
    }

    private static Extracted refuse(Call call, String why) {
        return new Extracted(List.of(), "#" + call.seq() + " " + call.tool() + ": " + why);
    }

    static ObjectNode object(String json) {
        try {
            JsonNode node = json == null || json.isBlank() ? null : JSON.readTree(json);
            return node instanceof ObjectNode object ? object.deepCopy() : JSON.createObjectNode();
        } catch (Exception notJson) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? "" : value.asText().strip();
    }
}
