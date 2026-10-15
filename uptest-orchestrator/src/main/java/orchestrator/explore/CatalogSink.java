package orchestrator.explore;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalog arrives as a TOOL CALL, not as the model's closing paragraph.
 *
 * Asking for "YAML and nothing else" never fully worked: the model wrote pages
 * of reasoning first and the document after, and deciding when it was done was
 * left to it — which is how one run finished the flow and then started it
 * again. Handing it a tool fixes all three at once:
 *
 *   - Shape is enforced by the tool's schema, not by the model's discipline.
 *     "type" is an enum here, so "string" or "textbox" cannot be written at
 *     all; a missing label is a schema error the model is told about.
 *   - The YAML is rendered by {@link #toYaml}, from the arguments. No prose
 *     can leak into it because none of it is the model's text.
 *   - Calling the tool IS "I am finished". Nothing has to be inferred from
 *     how a sentence ends.
 *
 * One instance per request: it holds that request's answer.
 */
public class CatalogSink {

    /**
     * @param label   exactly as the screen prints it
     * @param type    one of the SCHEMA.md types
     * @param options a dropdown's option texts, or an account picker's numbers
     */
    public record Field(
            @ToolParam(description = "Field label, exactly as shown on screen, Turkish characters included")
            String label,

            @ToolParam(description = "One of: text, date, amount, dropdown, account_picker, checkbox")
            String type,

            @ToolParam(description = "Selectable option texts for dropdown, or full account numbers "
                    + "for account_picker — you must OPEN the control to read them. Leave out the "
                    + "placeholder (Seçiniz). Empty list for every other type.")
            List<String> options,

            @ToolParam(required = false, description = "true when the application fills this field "
                    + "itself and a scenario must never type into it")
            Boolean readonly,

            @ToolParam(required = false, description = "true ONLY if you saw this value again on a "
                    + "confirmation or summary screen")
            Boolean verifiable,

            @ToolParam(required = false, description = "Why you are unsure about this field, if you are. "
                    + "Written into the catalog as a comment for a human to check.")
            String uncertain,

            @ToolParam(description = "If this field was NOT on the screen until another field was "
                    + "given a particular value, the label of that other field. \"\" otherwise.")
            String shownWhenField,

            @ToolParam(description = "The value that other field had to hold. \"\" otherwise.")
            String shownWhenValue
    ) {
    }

    /**
     * Fields noted during the walk, in the order they were seen.
     *
     * Keyed by label so a second look at the same field corrects the first
     * rather than duplicating it — a model that re-reads a field after
     * choosing a value is doing the right thing, and the later reading is the
     * better one.
     */
    private final Map<String, Field> noted = new LinkedHashMap<>();

    private String yaml;

    /**
     * Labels the confirmation screen showed that no note_field ever recorded.
     *
     * A field the application echoes back but the walk never saw is a hole in
     * the catalog, and the only place it is visible is here — the YAML looks
     * complete without it. Reported alongside the draft rather than guessed at.
     */
    private List<String> missedOnConfirmation = List.of();

    /**
     * One field, recorded the moment it was examined.
     *
     * Flat arguments on purpose. The alternative — one closing call carrying
     * the whole field list as nested objects — asks a lot of a smaller model,
     * and everything it saw early has to survive forty turns of snapshots
     * before it can be written down. Noting as it goes keeps each call easy
     * and each observation fresh, and leaves a partial catalog behind if the
     * run ends badly.
     */
    @Tool(name = "note_field",
            description = "Record ONE field you just examined. Call this for each field as you go, "
                    + "before moving on. Calling it again for the same label replaces the earlier note.")
    public String noteField(
            @ToolParam(description = "Field label, exactly as shown on screen") String label,
            @ToolParam(description = "One of: text, date, amount, dropdown, account_picker, checkbox") String type,
            @ToolParam(description = "Option texts for a dropdown, or account numbers for an "
                    + "account_picker — you must OPEN the control to read them. Leave out the "
                    + "placeholder (Seçiniz). Pass an empty list for every other type.")
            List<String> options,
            @ToolParam(required = false, description = "true when the application fills this field itself")
            Boolean readonly,
            @ToolParam(required = false, description = "Leave this out while filling a form — you cannot "
                    + "know it yet. Use note_confirmation later instead. Set it only when the screen you "
                    + "are reading IS itself a read-only summary.") Boolean verifiable,
            @ToolParam(required = false, description = "Why you are unsure, if you are") String uncertain,
            @ToolParam(description = "If this field was NOT on the screen until another field was "
                    + "given a particular value, the label of that other field — the one whose choice "
                    + "revealed this one. \"\" when it was there from the start.")
            String shownWhenField,
            @ToolParam(description = "The value that other field had to hold for this one to appear. "
                    + "\"\" when it was there from the start.") String shownWhenValue) {

        if (label == null || label.isBlank()) {
            return "A field needs a label. Nothing was recorded.";
        }
        String key = label.trim();
        noted.put(key, new Field(key, type, options, readonly, verifiable, uncertain,
                shownWhenField, shownWhenValue));

        // Recorded FIRST, then argued with. Refusing the note outright would
        // lose the field entirely whenever the agent reads the complaint and
        // moves on anyway; this way the worst case is a field with no options,
        // which is where we already were.
        if (unexplainedEmptyChoice(type, options, uncertain)) {
            return emptyChoiceComplaint(key, type);
        }
        return "Noted \"" + key + "\" (" + noted.size() + " fields so far). Continue.";
    }

    /**
     * A choice field recorded with nothing to choose from, and no reason given.
     *
     * The distinction this draws is the whole point: an empty option list means
     * two different things and until now they were written down identically.
     * "I did not open it" is a hole in the walk. "I opened it and it was empty
     * because its parent field is not set yet" is a fact about the screen —
     * "Döviz Cinsi" genuinely has no options until "Bayi Kodu" is chosen. The
     * second is allowed through; it just has to say so, and {@code uncertain}
     * already carries that into the YAML as a comment a human can act on.
     */
    private static boolean unexplainedEmptyChoice(String type, List<String> options, String uncertain) {
        return CHOICE_TYPES.contains(type)
                && (options == null || options.isEmpty())
                && (uncertain == null || uncertain.isBlank());
    }

    /** The types whose whole purpose is a list of things to pick from. */
    private static final List<String> CHOICE_TYPES = List.of("dropdown", "account_picker");

    /**
     * What to do about it, said WHILE the agent is still on that screen.
     *
     * The system prompt already explains this at length and in capitals, and
     * the agent still returned four dropdowns with no options. A prompt is read
     * once, at the start of a forty-turn walk; a tool's return value arrives at
     * the exact moment the mistake is made, on the screen where it can still be
     * fixed. {@code note_confirmation} established that this works — it reports
     * unmatched labels back and that is how missed confirmation rows surface.
     */
    private static String emptyChoiceComplaint(String label, String type) {
        return "RECORDED, BUT INCOMPLETE: \"" + label + "\" is a " + type
                + " and you gave no options and no reason.\n"
                + "A closed dropdown on this application contains NOTHING — the list is built "
                + "when you click it, so a snapshot of the closed control shows no choices at all.\n"
                + "Do one of these two things now, before moving on:\n"
                + "  1. Click it open, read the list, close it, and call note_field again for "
                + "\"" + label + "\" with the options. This is almost always the right one.\n"
                + "  2. If you DID open it and it really was empty — a field whose options only "
                + "load once another field is chosen — call note_field again for \"" + label
                + "\" with the same empty list AND put the reason in `uncertain`, "
                + "e.g. \"opened it, empty until Bayi Kodu is chosen\".\n"
                + "A " + type + " with no options is a field a scenario cannot use: it has no "
                + "idea what it is allowed to pick.";
    }

    /**
     * The confirmation screen, read in ONE call at the one moment it is visible.
     *
     * verifiable started as an argument of note_field and came back empty every
     * run — not from carelessness but because the question was asked before the
     * answer could exist. A field is noted while the FORM is being filled;
     * whether the application echoes its value back is only knowable after the
     * submit, on a screen the agent has not opened yet. Asked "did you see this
     * again?" about a screen it has not reached, the only honest answer is no,
     * and nothing ever brings it back to amend fifteen earlier calls.
     *
     * So the question moved to where the answer is. One call, on the
     * confirmation screen, naming what that screen shows again.
     *
     * Labels that match nothing are reported back rather than dropped: a label
     * on the confirmation screen that was never noted is a field the walk
     * missed, and that is worth saying while the agent can still fix it.
     */
    @Tool(name = "note_confirmation",
            description = "Call this ONCE while a confirmation or summary screen is in front of you, "
                    + "before pressing the final confirm button. Pass every label that screen prints "
                    + "back to you. Those fields become verifiable in the catalog — without this call "
                    + "the catalog can only check the success banner and nothing else.")
    public String noteConfirmation(
            @ToolParam(description = "Labels echoed on the confirmation screen, exactly as printed there. "
                    + "Include every row of a summary table.") List<String> labels) {

        if (labels == null || labels.isEmpty()) {
            return "No labels given, so nothing was marked verifiable. If this screen really "
                    + "echoes none of the values, carry on.";
        }

        List<String> unknown = markVerifiable(labels);

        StringBuilder reply = new StringBuilder();
        reply.append(countVerifiable()).append(" field(s) marked verifiable");
        if (!unknown.isEmpty()) {
            reply.append(". These are on the confirmation screen but were never recorded with "
                            + "note_field, so the walk missed them: ")
                    .append(String.join(", ", unknown))
                    .append(" — record each one now with note_field");
        }
        return reply.append(". Then carry on.").toString();
    }

    /**
     * Flags every noted field named in {@code labels}.
     *
     * @return the labels that matched nothing — fields the walk never recorded
     */
    private List<String> markVerifiable(List<String> labels) {
        List<String> unknown = new ArrayList<>();
        for (String raw : labels == null ? List.<String>of() : labels) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = keyOf(raw.trim());
            if (key == null) {
                unknown.add(raw.trim());
                continue;
            }
            Field was = noted.get(key);
            noted.put(key, new Field(was.label(), was.type(), was.options(),
                    was.readonly(), true, was.uncertain(),
                    was.shownWhenField(), was.shownWhenValue()));
        }
        return unknown;
    }

    private long countVerifiable() {
        return noted.values().stream().filter(f -> Boolean.TRUE.equals(f.verifiable())).count();
    }

    /**
     * The noted label this confirmation label refers to, or null.
     *
     * Exact first, then ignoring case and spacing, because a confirmation
     * screen is a different template from the form and prints "Fatura  No"
     * where the form printed "Fatura No". Matching loosely here is safe: it
     * only ever sets a flag, and being wrong costs one extra assertion rather
     * than a wrong value.
     */
    private String keyOf(String label) {
        if (noted.containsKey(label)) {
            return label;
        }
        String wanted = loose(label);
        for (String key : noted.keySet()) {
            if (loose(key).equals(wanted)) {
                return key;
            }
        }
        return longestPrefix(wanted);
    }

    /**
     * The noted label this text BEGINS with, longest first.
     *
     * A confirmation screen prints the label and its value as one run of text,
     * so an agent told to report labels "exactly as printed there" reports
     * "Bayi Kodu 93002" — and it is right to. Demanding equality threw away
     * all fifteen labels of a correctly walked flow and left every field
     * unverifiable, which then looked like the agent had skipped the screen.
     *
     * Two guards keep it from over-matching:
     *
     *   - the character after the prefix must not be a letter, or the noted
     *     "Fatura No" swallows a printed "Fatura Numarası 955231". Both labels
     *     live on this very form, so the boundary is not hypothetical.
     *   - the LONGEST match wins, so "Taksit Periyodu 2" lands on "Taksit
     *     Periyodu" and not on "Taksit Periyot Birimi".
     *
     * Matching loosely is safe here for the same reason the case-folding above
     * is: it only ever sets a flag. Being wrong costs one extra assertion, not
     * a wrong value.
     */
    private String longestPrefix(String wanted) {
        String best = null;
        int matched = 0;
        for (String key : noted.keySet()) {
            String candidate = loose(key);
            if (candidate.length() <= matched || !wanted.startsWith(candidate)) {
                continue;
            }
            if (wanted.length() > candidate.length()
                    && Character.isLetter(wanted.charAt(candidate.length()))) {
                continue;
            }
            best = key;
            matched = candidate.length();
        }
        return best;
    }

    /**
     * Turkish locale on purpose. A confirmation screen often shouts its labels
     * in capitals, and only the Turkish rules fold "FATURA TİPİ" onto
     * "Fatura Tipi" — the root locale lowercases "İ" to an i with a separate
     * combining dot, which then matches nothing.
     */
    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    private static String loose(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(TR);
    }

    /**
     * Called by the model when the exploration is over.
     *
     * The return value is addressed to the model: it has to read as "done",
     * or it goes looking for more to do.
     */
    /**
     * returnDirect ENDS THE LOOP. Without it the model was merely ASKED to stop
     * — the tool answered "the task is complete, call no more tools" and it
     * carried on anyway, walking the flow a second time. A request that never
     * comes back also leaves the UI's button disabled forever, because the
     * button is re-enabled when the response arrives.
     *
     * With returnDirect the result is handed straight back to the caller
     * instead of to the model, so there is no next turn to keep going in. The
     * same move as every other guard here: a prompt rule that became code.
     */
    @Tool(name = "submit_catalog", returnDirect = true,
            description = "Finish the task. Call this exactly once, after you have noted every field "
                    + "with note_field. It closes the catalog with how the screen is reached and what "
                    + "the flow presses. Calling it ends the task immediately.")
    public String submitCatalog(
            @ToolParam(description = "NGC or NGI") String app,
            @ToolParam(description = "Human name of the flow, e.g. 'Fatura Oluştur'") String flow,
            @ToolParam(description = "Top menu caption, e.g. 'NAKİT YÖNETİMİ'") String mainMenu,
            @ToolParam(description = "Sub menu caption") String subMenu,
            // These three are REQUIRED with "" as the way to say "there was
            // none", and that is the whole point. As optional parameters they
            // came back null run after run even when the instruction spelled
            // the values out literally — a model fills what the schema demands
            // and skips what it merely permits. Every required argument of this
            // tool arrives; almost no optional one does. Same fix, same reason
            // as confirmedLabels below.
            @ToolParam(description = "Tab or filter you had to switch to after the sub-menu, before "
                    + "anything else — e.g. a list that opens on one filter while the records you want "
                    + "are under another. Pass \"\" if there was none.") String entryTab,
            @ToolParam(description = "Result-grid row you picked BEFORE the entry button, when the "
                    + "sub-menu opened a list rather than a form. This is the row you actually clicked, "
                    + "not a description of it. Pass \"\" if the sub-menu opened the form directly.")
            String entryRow,
            @ToolParam(description = "Button or link that opened the form, as you clicked it. "
                    + "Pass \"\" if the sub-menu opened the form directly.") String entryButton,
            @ToolParam(description = "Radio option you picked when the entry button opened a FORK "
                    + "rather than a form — a small group of choices that decides WHICH screen comes "
                    + "next, e.g. entering data by hand versus uploading a file. Pass the option you "
                    + "chose, exactly as it is printed. Pass \"\" if the button opened the form "
                    + "straight away.") String entryChoice,
            @ToolParam(required = false, description = "Only for fields you did NOT already record "
                    + "with note_field. Normally leave this out.") List<Field> fields,
            @ToolParam(required = false, description = "Buttons the flow presses, e.g. Devam, Onay")
            List<String> buttons,
            @ToolParam(description = "The success text on the RESULT screen, verbatim. Pass \"\" ONLY "
                    + "if you never reached that screen — and if you pass \"\", say so in your answer, "
                    + "because a catalog without it cannot prove the transaction completed; it can only "
                    + "check the confirmation screen.") String successMessage,
            @ToolParam(description = "Every label the CONFIRMATION screen showed back to you on the "
                    + "way here. If you are filling in successMessage then you DID pass a confirmation "
                    + "screen, so this cannot be empty. A confirmation screen normally repeats MOST of "
                    + "the form: expect a list nearly as long as your field list, and if you are about "
                    + "to send one or two labels for a form of fifteen, you read only the top of the "
                    + "table — scroll it to the end first. Pass an empty list ONLY if this flow never "
                    + "showed you your values again. Without these the catalog can check nothing but "
                    + "the success banner, and a field that silently stayed empty still passes.")
            List<String> confirmedLabels) {

        // Anything passed here joins what was noted along the way, and a
        // repeated label corrects the earlier note rather than duplicating it.
        for (Field field : fields == null ? List.<Field>of() : fields) {
            if (field != null && field.label() != null && !field.label().isBlank()) {
                noted.put(field.label().trim(), field);
            }
        }

        // The same information note_confirmation takes, on the call the agent
        // never forgets. note_confirmation is a separate, optional, mid-flow
        // call and it was skipped run after run; submit_catalog is the one call
        // that always happens, because the catalog does not exist without it.
        // Harmless when note_confirmation did run — flagging a flagged field
        // changes nothing.
        this.missedOnConfirmation = markVerifiable(confirmedLabels);

        this.yaml = toYaml(app, flow, mainMenu, subMenu, entryTab, entryRow, entryButton,
                entryChoice, new ArrayList<>(noted.values()), buttons, successMessage);
        return "Catalog received with " + noted.size()
                + " fields. The task is complete — do not call any more tools.";
    }

    /**
     * The catalog as it stands right now, even if submit_catalog was never
     * reached. A run that died after noting ten fields still knows ten fields,
     * and throwing that away would make every failure cost the whole walk.
     */
    public String partialYaml() {
        return noted.isEmpty() ? null : toYaml("NGC", "", "", "", null, null, null, null,
                new ArrayList<>(noted.values()), null, null);
    }

    /** The catalog this request produced, or null when the tool was never called. */
    public String yaml() {
        return yaml;
    }

    /** Confirmation-screen labels that match no field in the catalog. */
    public List<String> missedOnConfirmation() {
        return missedOnConfirmation;
    }

    /**
     * Choice fields that came back with no options, mapped to the reason the
     * agent gave — or to "" when it gave none.
     *
     * Exists because the reason cannot be recovered from the YAML. It is
     * written there as a {@code # emin degil:} COMMENT, and the problem report
     * reads the draft back through {@link orchestrator.catalog.CatalogModel},
     * which parses YAML — so SnakeYAML drops the comment before anything can
     * see it. The sink is the only place that still knows, which is the same
     * reason {@link #missedOnConfirmation()} is reported from here rather than
     * derived from the document.
     *
     * The distinction is worth the method: "nobody opened it" and "it was
     * genuinely empty until its parent is chosen" are both incomplete
     * catalogs, but one is a walk to redo and the other is a line for an
     * analyst to fill in by hand.
     */
    public Map<String, String> emptyChoiceReasons() {
        Map<String, String> empties = new LinkedHashMap<>();
        for (Field field : noted.values()) {
            if (CHOICE_TYPES.contains(field.type())
                    && (field.options() == null || field.options().isEmpty())) {
                empties.put(field.label(),
                        field.uncertain() == null ? "" : field.uncertain().trim());
            }
        }
        return empties;
    }

    /**
     * The field labels recorded so far.
     *
     * Read by BudgetedTool to tell a FIELD apart from everything else on the
     * page. The agent names what it is acting on, but "Garanti Tutarı lira
     * kutusu" and "25 gün hücresi" look alike from outside; only the agent's
     * own note_field calls say which of them is a field of this form. That is
     * what makes "never touch a field the instruction did not mention"
     * enforceable without also blocking a calendar cell or a dropdown option.
     */
    public List<String> labels() {
        return List.copyOf(noted.keySet());
    }

    /** The type recorded for a label, or null if it was never noted. */
    public String typeOf(String label) {
        Field field = noted.get(label);
        return field == null ? null : field.type();
    }

    /**
     * True once submit_catalog has been called — the task is over.
     *
     * Read by BudgetedTool, which refuses every tool afterwards. returnDirect
     * should already have ended the loop; this is the backstop for the case
     * where it does not, because an agent that keeps browsing after it is
     * finished holds the HTTP request open and the analyst's button with it.
     */
    public boolean isClosed() {
        return yaml != null;
    }

    /**
     * Renders SCHEMA.md's shape. Hand-written rather than a YAML library
     * because the output is a small fixed document and quoting every scalar
     * keeps Turkish text and colons safe without a dependency.
     */
    private static String toYaml(String app, String flow, String mainMenu, String subMenu,
                                 String entryTab, String entryRow, String entryButton,
                                 String entryChoice,
                                 List<Field> fields, List<String> buttons, String successMessage) {
        StringBuilder out = new StringBuilder();
        out.append("app: ").append("NGI".equalsIgnoreCase(app) ? "NGI" : "NGC").append('\n');
        out.append("flow: ").append(text(flow)).append("\n\n");

        out.append("navigation:\n");
        out.append("  main_menu: \"").append(escape(mainMenu)).append("\"\n");
        out.append("  sub_menu: \"").append(escape(subMenu)).append("\"\n");
        // Order is SCHEMA.md's order, and it is the order they are performed:
        // menu, sub-menu, tab, row, button, choice. entry_tab used to be
        // accepted as an argument and then dropped here, which is why the Banko
        // catalog had nothing about the "Ödenecekler" tab the agent had actually
        // clicked. entry_choice was worse: the schema documented it, the model
        // was shown the schema, and no argument existed to answer with — so the
        // DBS radio fork could not be reported at all. CatalogContractTest now
        // fails if a documented key stops arriving here.
        if (present(entryTab)) {
            out.append("  entry_tab: \"").append(escape(entryTab)).append("\"\n");
        }
        if (present(entryRow)) {
            out.append("  entry_row: \"").append(escape(entryRow)).append("\"\n");
        }
        if (present(entryButton)) {
            out.append("  entry_button: \"").append(escape(entryButton)).append("\"\n");
        }
        if (present(entryChoice)) {
            out.append("  entry_choice: \"").append(escape(entryChoice)).append("\"\n");
        }

        out.append("\nfields:\n");
        for (Field field : fields == null ? List.<Field>of() : fields) {
            if (field == null || !present(field.label())) {
                continue;
            }
            if (present(field.uncertain())) {
                // The model's own doubt, kept where a reviewer will see it.
                out.append("  # emin degil: ").append(oneLine(field.uncertain())).append('\n');
            }
            out.append("  - label: \"").append(escape(field.label())).append("\"\n");
            out.append("    type: ").append(text(field.type())).append('\n');
            if (Boolean.TRUE.equals(field.readonly())) {
                out.append("    readonly: true\n");
            }
            if (Boolean.TRUE.equals(field.verifiable())) {
                out.append("    verifiable: true\n");
            }
            // The nested {field, value} shape CatalogModel.read() looks for.
            // A field that only exists once another holds a value is NOT
            // optional: a scenario that fills it while the trigger says
            // something else hunts for an element that is not on the page until
            // it times out, and one that skips it leaves a required box empty.
            if (present(field.shownWhenField())) {
                out.append("    shown_when:\n");
                out.append("      field: \"").append(escape(field.shownWhenField())).append("\"\n");
                out.append("      value: \"").append(escape(field.shownWhenValue())).append("\"\n");
            }
            appendList(out, "    ", keyFor(field.type()), field.options());
        }

        appendList(out, "", "buttons", buttons);
        if (present(successMessage)) {
            out.append("\nsuccess_message: \"").append(escape(successMessage)).append("\"\n");
        }
        return out.toString();
    }

    /** SCHEMA.md names the same list differently per type. */
    private static String keyFor(String type) {
        return "account_picker".equals(type) ? "accounts" : "options";
    }

    private static void appendList(StringBuilder out, String indent, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        out.append(indent).append(key).append(":\n");
        for (String value : values) {
            if (present(value)) {
                out.append(indent).append("  - \"").append(escape(value)).append("\"\n");
            }
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(String value) {
        return present(value) ? value.trim() : "";
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String escape(String value) {
        return text(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
