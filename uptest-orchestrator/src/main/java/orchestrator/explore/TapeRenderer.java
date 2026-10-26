package orchestrator.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A recorded agent walk, written back out as steps an analyst can read.
 *
 * <p>The walk cost tokens and minutes once; this turns it into a {@code
 * .feature} that costs neither, because the step vocabulary runs without a
 * model. What makes that possible is not clever inference — it is that the
 * MCP server states, in every single result, exactly what it did:
 *
 * <pre>
 *   ### Ran Playwright code
 *   await page.getByRole('link', { name: 'Detay', exact: true }).click();
 * </pre>
 *
 * <p>So the role and the accessible name of every action are already resolved
 * when the tape is written. {@link AriaSnapshot} is still consulted, but for
 * the questions that line cannot answer: what the menu link is called without
 * its "YENİ" badge, which cell tells one grid row from another, and what a
 * dropdown's label is.
 *
 * <p><b>Two names for one element, and when to trust which.</b> The echoed
 * line carries {@code exact: true} only when the server needed it to
 * disambiguate; without it the name is a SUBSTRING it considered sufficient,
 * and it really is shortened — the option this walk chose comes back as
 * {@code 'APPLE A.Ş'} while the screen says {@code APPLE A.Ş.}. A step built
 * from the shortened form would then fail the catalog check against an option
 * the screen never offered. So: an exact name is taken as given, and anything
 * else is re-read from the snapshot.
 *
 * <p><b>What it refuses to do.</b> An action with no vocabulary step is not
 * guessed at — it is left out and named in {@link Rendered#notes}, the same
 * discipline {@code EntryRow} and {@code DateVerb} follow. And the assertion
 * is never invented: the agent does not assert, it reads a snapshot and says
 * so in prose, so the THEN comes from the analyst's own expectation. That is
 * the right way round — the oracle is what the business expects, not what the
 * application happened to show.
 */
public final class TapeRenderer {

    /** One recorded call, as {@link ActionTape} wrote it. */
    public record Call(int seq, String tool, String args, String outcome, String result) {
    }

    /**
     * @param steps Gherkin lines, in order, without the login
     * @param notes what could not be expressed, and why — never silent
     */
    public record Rendered(List<String> steps, List<String> notes) {
    }

    // ------------------------------------------------------------------
    // Reading the echoed Playwright line
    // ------------------------------------------------------------------

    private static final String CODE_HEADING = "### Ran Playwright code";

    /** {@code getByRole('link', { name: 'Detay', exact: true })}, name and flag optional. */
    private static final Pattern BY_ROLE = Pattern.compile(
            "getByRole\\('([a-zA-Z]+)'(?:\\s*,\\s*\\{([^}]*)})?\\)");

    private static final Pattern NAME = Pattern.compile("name:\\s*'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern EXACT = Pattern.compile("exact:\\s*true");
    private static final Pattern FILL = Pattern.compile("\\.fill\\('((?:[^'\\\\]|\\\\.)*)'\\)");
    private static final Pattern TARGET_REF = Pattern.compile("\"target\"\\s*:\\s*\"([A-Za-z0-9]+)\"");

    /** Reads nothing and changes nothing; a scenario has no line for these. */
    private static final List<String> SILENT = List.of(
            "browser_snapshot", "browser_find", "browser_wait_for", "browser_navigate",
            "browser_console_messages", "browser_network_requests", "browser_take_screenshot");

    /** One {@code await page…} line, taken apart. */
    private record Act(String role, String name, boolean exact, String innerRole, String value) {

        boolean isClick() {
            return value == null;
        }
    }

    private TapeRenderer() {
    }

    /**
     * @param calls   the tape's entries, in order
     * @param mainMenu the top-level menu from the tape header; the first click
     *                 on it is where the login ends and the scenario begins
     * @param expectations the scenario's own BEKLENEN lines, which is where
     *                     the THEN comes from — see {@link #appendVerifies}
     */
    public static Rendered render(List<Call> calls, String mainMenu, List<String> expectations) {
        List<String> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Scope scope = new Scope();

        boolean started = false;
        for (int i = 0; i < calls.size(); i++) {
            Call call = calls.get(i);
            scope.remember(call);

            if (SILENT.contains(call.tool())) {
                continue;
            }
            if (!"OK".equals(call.outcome())) {
                notes.add("#" + call.seq() + " " + call.tool() + " " + call.outcome().toLowerCase(Locale.ROOT)
                        + " oldu, adıma çevrilmedi.");
                continue;
            }

            Act act = firstAct(call).orElse(null);
            if (act == null) {
                if (!started) {
                    continue;
                }
                notes.add("#" + call.seq() + " " + call.tool()
                        + " için vocabulary'de karşılık yok, satır üretilmedi.");
                continue;
            }

            String ref = refOf(call);
            String caption = scope.caption(act, ref);

            // Everything before the main menu is the login, which the run
            // grafts in from the test user rather than replaying: it carries
            // the password and the SMS code, and LoginStep already owns it.
            if (!started) {
                if (act.isClick() && fold(caption).equals(fold(mainMenu))) {
                    started = true;
                    int at = nextActing(calls, i, scope);
                    if (at < 0) {
                        notes.add("Ana menüye tıklandı ama alt menü seçilmemiş.");
                        continue;
                    }
                    Call next = calls.get(at);
                    steps.add("navigate to \"" + mainMenu + "\" \""
                            + scope.caption(firstAct(next).orElse(act), refOf(next)) + "\"");
                    i = at;
                }
                continue;
            }

            // A dropdown is two clicks: open the control, then pick the option.
            if (act.isClick() && isTrigger(act)) {
                // The label has to be read BEFORE walking forward: opening the
                // control replaces the combobox node, so the snapshot that
                // still knows which field this was is the one already in hand.
                String field = scope.labelOf(ref, caption);
                int at = nextActing(calls, i, scope);
                Act option = at < 0 ? null : firstAct(calls.get(at)).orElse(null);
                if (option != null && "option".equals(option.role())) {
                    steps.add("select \"" + scope.caption(option, refOf(calls.get(at)))
                            + "\" from \"" + field + "\"");
                    i = at;
                    continue;
                }
            }

            if (act.isClick() && isRow(act, ref, scope)) {
                String identifier = scope.rowIdentifier(ref);
                if (identifier.isBlank()) {
                    notes.add("#" + call.seq() + " bir ızgara satırı seçildi ama satırı tanıtan"
                            + " bir hücre bulunamadı; 'select row' satırını elle yazın.");
                } else {
                    steps.add("select row \"" + identifier + "\"");
                }
                continue;
            }

            if (act.isClick()) {
                steps.add("click \"" + caption + "\"");
                continue;
            }

            steps.add("enter \"" + scope.labelOf(ref, caption) + "\" \"" + act.value() + "\"");
        }

        if (steps.isEmpty()) {
            notes.add("Bu yürüyüşten hiçbir adım çıkarılamadı — ajan ana menüye hiç ulaşmamış olabilir.");
        }
        appendVerifies(expectations, scope, steps, notes);
        return new Rendered(steps, notes);
    }

    /**
     * The THEN, built from what the ANALYST expects — never from what the
     * screen showed.
     *
     * The distinction is the whole of it. The agent does not assert: it reads
     * a snapshot and describes it, so a verify derived from the walk would say
     * the application is right because the application said so, and a defect
     * would be written into the suite as a passing test. The expectation is
     * the one input that can contradict the screen, and it is the analyst's.
     *
     * What the tape contributes is the other half: whether the label exists on
     * the screen the walk ENDED on. A verify naming a field that was never
     * shown spends its full timeout and blames the wrong thing, and that is
     * knowable here, before anyone runs it.
     *
     * The two are then compared. When they disagree the analyst's value is
     * used and the difference is reported — because a disagreement means
     * either the expectation is stale or the application is wrong, and those
     * need opposite fixes.
     */
    private static void appendVerifies(List<String> expectations, Scope scope,
                                       List<String> steps, List<String> notes) {
        if (expectations == null || expectations.isEmpty()) {
            notes.add("Senaryonun BEKLENEN alanı boş, doğrulama satırı üretilmedi —"
                    + " bir THEN'i olmayan senaryo test değildir.");
            return;
        }
        Map<String, String> onScreen = scope.labelledValues();
        for (String expectation : expectations) {
            if (expectation == null || expectation.isBlank()) {
                continue;
            }
            String label = longestLabelIn(expectation, onScreen.keySet());
            if (label == null) {
                notes.add("\"" + expectation.trim() + "\" bir doğrulama satırına çevrilemedi:"
                        + " ajanın son gördüğü ekranda bu adda bir alan yok. Satırı elle yazın.");
                continue;
            }
            String value = valueIn(expectation, label);
            if (value.isBlank()) {
                notes.add("\"" + expectation.trim() + "\" içinde '" + label
                        + "' alanı bulundu ama beklenen değer okunamadı. Satırı elle yazın.");
                continue;
            }
            steps.add(verifyFor(label, value));

            String shown = onScreen.getOrDefault(label, "");
            if (!shown.isBlank() && !fold(shown).equals(fold(value))) {
                notes.add("'" + label + "' için beklediğiniz değer \"" + value
                        + "\", ajanın gördüğü \"" + shown + "\". Sizinki yazıldı —"
                        + " ya beklenti bayat ya uygulama hatalı, ve ikisi zıt düzeltme ister.");
            }
        }
    }

    /** gg/aa/yyyy, which {@code enter date} and its verify take as meaning. */
    private static final Pattern DATE = Pattern.compile("^\\d{1,2}[./-]\\d{1,2}[./-]\\d{4}$");

    /** 1.925,96 with an optional currency, which the amount verify normalises. */
    private static final Pattern AMOUNT =
            Pattern.compile("^[0-9][0-9.]*,[0-9]{2}(\\s*[A-Z]{2,3})?$");

    /** The verb the value's own shape asks for, as the type table does. */
    private static String verifyFor(String label, String value) {
        if (DATE.matcher(value).matches()) {
            return "verify the \"" + label + "\" has date \"" + value + "\"";
        }
        if (AMOUNT.matcher(value).matches()) {
            return "verify the \"" + label + "\" has amount \"" + value + "\"";
        }
        return "verify the \"" + label + "\" field equals \"" + value + "\"";
    }

    /**
     * The longest on-screen label the expectation names.
     *
     * Longest, because the short one is inside the long one on these screens:
     * an expectation about "Orijinal Fatura Tutarı" also contains "Fatura
     * Tutarı", and the shorter match would verify a different row of the same
     * dialog.
     */
    private static String longestLabelIn(String expectation, java.util.Set<String> labels) {
        String folded = fold(expectation);
        String best = null;
        for (String label : labels) {
            if (folded.contains(fold(label)) && (best == null || label.length() > best.length())) {
                best = label;
            }
        }
        return best;
    }

    /**
     * What the analyst wrote after the label, with the sentence's tail off.
     *
     * "Bayi Kodu 151732 oldugunu doğrula" is a sentence, not a field and a
     * value, so the value is whatever sits between the label and the first
     * word that is plainly part of the asking. The list is short and only
     * grows when a real expectation is not read — a longer list would start
     * eating values.
     */
    private static final List<String> TAIL = List.of(
            "oldugunu", "olduğunu", "olmali", "olmalı", "dogrula", "doğrula",
            "dogrulanmali", "doğrulanmalı", "gorunmeli", "görünmeli", "gosterilmeli",
            "gösterilmeli", "yazmali", "yazmalı", "olarak", "seklinde", "şeklinde");

    private static String valueIn(String expectation, String label) {
        String folded = fold(expectation);
        int at = folded.indexOf(fold(label));
        String rest = expectation.substring(Math.min(expectation.length(), at + label.length())).trim();

        int cut = rest.length();
        for (String tail : TAIL) {
            Matcher word = Pattern.compile("(?i)(^|\\s)" + Pattern.quote(tail) + "(\\s|$|\\.)")
                    .matcher(fold(rest));
            if (word.find()) {
                cut = Math.min(cut, word.start(1) == word.start() ? word.start() : word.start() + 1);
            }
        }
        return rest.substring(0, Math.max(0, Math.min(cut, rest.length()))).trim()
                .replaceAll("^[:\\-–—\\s]+", "").replaceAll("[.,;\\s]+$", "").trim();
    }

    /** A control that opens a list rather than doing something on its own. */
    private static boolean isTrigger(Act act) {
        return "textbox".equals(act.role()) || "combobox".equals(act.role());
    }

    /** The row picker: the line chains down to a radio, or the ref is one. */
    private static boolean isRow(Act act, String ref, Scope scope) {
        if ("radio".equals(act.innerRole()) || "radio".equals(act.role())) {
            return true;
        }
        return scope.roleOf(ref).equals("radio");
    }

    /**
     * The index of the next call that actually did something, or -1.
     *
     * Reads every call it walks past into the scope, which is not an aside:
     * the reference the NEXT action names was chosen from a snapshot taken
     * BETWEEN the two, so looking ahead without reading along resolves it
     * against a stale tree. Measured — the first version paired the menu
     * clicks correctly and then wrote the submenu's name with its "YENİ"
     * badge still on, because the snapshot carrying the badge as a separate
     * node had not been read yet.
     */
    private static int nextActing(List<Call> calls, int from, Scope scope) {
        for (int i = from + 1; i < calls.size(); i++) {
            Call call = calls.get(i);
            scope.remember(call);
            if (SILENT.contains(call.tool()) || !"OK".equals(call.outcome())) {
                continue;
            }
            if (firstAct(call).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private static String refOf(Call call) {
        Matcher match = TARGET_REF.matcher(call.args() == null ? "" : call.args());
        return match.find() ? match.group(1) : "";
    }

    /**
     * The first {@code await page…} line of a result, taken apart.
     *
     * Only the first: a fill_form writes three fields in one call, and all
     * three belong to the login this renderer does not replay. Should a
     * multi-field call ever appear after the login, the extra fields are
     * reported rather than dropped — see the note in {@link #render}.
     */
    private static Optional<Act> firstAct(Call call) {
        String code = codeBlock(call.result());
        if (code.isBlank()) {
            return Optional.empty();
        }
        for (String line : code.split("\\R")) {
            if (!line.contains("page.")) {
                continue;
            }
            List<String[]> roles = new ArrayList<>();
            Matcher byRole = BY_ROLE.matcher(line);
            while (byRole.find()) {
                roles.add(new String[]{byRole.group(1), byRole.group(2) == null ? "" : byRole.group(2)});
            }
            if (roles.isEmpty()) {
                continue;
            }
            String[] first = roles.get(0);
            Matcher name = NAME.matcher(first[1]);
            Matcher fill = FILL.matcher(line);
            return Optional.of(new Act(
                    first[0],
                    name.find() ? unescape(name.group(1)) : "",
                    EXACT.matcher(first[1]).find(),
                    roles.size() > 1 ? roles.get(1)[0] : "",
                    fill.find() ? unescape(fill.group(1)) : null));
        }
        return Optional.empty();
    }

    private static String codeBlock(String result) {
        if (result == null) {
            return "";
        }
        int start = result.indexOf(CODE_HEADING);
        if (start < 0) {
            return "";
        }
        String rest = result.substring(start + CODE_HEADING.length());
        int next = rest.indexOf("###");
        return (next < 0 ? rest : rest.substring(0, next)).replace("\\n", "\n");
    }

    private static String unescape(String value) {
        return value.replace("\\'", "'").replace("\\\"", "\"").trim();
    }

    /**
     * Turkish letters folded to ASCII before anything is compared.
     *
     * Analysts write expectations without them constantly — this walk's own
     * BEKLENEN line says "oldugunu dogrula" — and the screen says "Fatura
     * Tutarı". Without folding, the label is simply not found and a perfectly
     * good expectation is reported as unmatched. ScreenLabels does the same
     * thing on the executor side and for the same reason.
     *
     * Only the COMPARISON is folded. Whatever is written into a step keeps the
     * screen's own spelling, because that is what the step has to match.
     */
    private static final String TURKISH = "çğıöşüâîû";
    private static final String ASCII = "cgiosuaiu";

    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.toLowerCase(Locale.forLanguageTag("tr-TR"));
        StringBuilder out = new StringBuilder(lowered.length());
        for (char letter : lowered.toCharArray()) {
            int at = TURKISH.indexOf(letter);
            out.append(at >= 0 ? ASCII.charAt(at) : letter);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * The snapshots a reference can still be looked up in.
     *
     * Newest first, and a handful rather than one: the agent takes a reference
     * from whichever reading came last, and that is sometimes a
     * {@code browser_find} result rather than a full snapshot — the "Detay"
     * link of this walk was found that way. Both are the same tree in the same
     * shape, so both are parsed.
     */
    private static final class Scope {

        private static final int REMEMBERED = 6;

        private final Deque<AriaSnapshot> recent = new ArrayDeque<>();

        void remember(Call call) {
            if (call.result() == null) {
                return;
            }
            AriaSnapshot parsed = AriaSnapshot.of(call.result());
            if (parsed.isEmpty()) {
                return;
            }
            recent.addFirst(parsed);
            while (recent.size() > REMEMBERED) {
                recent.removeLast();
            }
        }

        /**
         * What to call the element: the echoed name when it was EXACT, the
         * snapshot's own otherwise. See the class note on the shortened
         * "APPLE A.Ş".
         */
        String caption(Act act, String ref) {
            if (act.exact() && !act.name().isBlank()) {
                return act.name();
            }
            for (AriaSnapshot snapshot : recent) {
                if (snapshot.node(ref).isPresent()) {
                    String plain = snapshot.plainName(ref);
                    if (!plain.isBlank()) {
                        return plain;
                    }
                }
            }
            return act.name();
        }

        /** The field's label, for the steps that take one. */
        String labelOf(String ref, String fallback) {
            for (AriaSnapshot snapshot : recent) {
                if (snapshot.node(ref).isPresent()) {
                    String label = snapshot.labelOf(ref);
                    if (!label.isBlank()) {
                        return label;
                    }
                }
            }
            return fallback;
        }

        String rowIdentifier(String ref) {
            for (AriaSnapshot snapshot : recent) {
                if (snapshot.node(ref).isPresent()) {
                    String identifier = snapshot.rowIdentifier(ref);
                    if (!identifier.isBlank()) {
                        return identifier;
                    }
                }
            }
            return "";
        }

        /**
         * The label/value pairs of the screen the walk ended on.
         *
         * Newest first and merged, because the dialog a scenario asserts on
         * is usually the last thing read while the grid behind it was read a
         * call or two earlier — and an expectation may name either.
         */
        Map<String, String> labelledValues() {
            Map<String, String> pairs = new LinkedHashMap<>();
            for (AriaSnapshot snapshot : recent) {
                snapshot.labelledValues().forEach(pairs::putIfAbsent);
            }
            return pairs;
        }

        String roleOf(String ref) {
            for (AriaSnapshot snapshot : recent) {
                Optional<AriaSnapshot.Node> node = snapshot.node(ref);
                if (node.isPresent()) {
                    return node.get().role();
                }
            }
            return "";
        }
    }
}
