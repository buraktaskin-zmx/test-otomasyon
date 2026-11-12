package orchestrator.explore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import orchestrator.explore.TapeRenderer.Call;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A passed walk, written down as steps that can be carried out again by NAME.
 *
 * <p><b>What is kept, and why not the references.</b> MCP's references
 * ({@code e42}, {@code f3e747}) belong to one page load — tape 2628c4fd saw
 * "Fatura İptal Et" as three different references in one walk. What survives
 * to the next run is what the page PRINTS, which is exactly what
 * {@link StepRunner} finds things by. So a cached step is a
 * {@link StepRunner.Step} — action, name, value — plus two things a replay
 * needs: how far a date lay from the day it was recorded, and the
 * expectation the scenario wrote under the step that produced it.
 *
 * <p><b>Where the steps come from.</b> A {@code do_steps} batch's own steps
 * (from {@link StepJournal}) wherever there was one — they say {@code row
 * "Ödenecek"} where the tape only has a click on a reference — and the
 * agent's single calls everywhere else, each resolved against the page it was
 * made on. A call that cannot be resolved into a step makes the whole walk
 * uncacheable: a replay that silently skips a step is a different scenario.
 */
final class CachedSteps {

    /**
     * @param dayOffset for a date: days from the recording day to the date
     *                  that was set; the replay sets today + this. Null otherwise.
     * @param expected  the expectation written under the scenario step this
     *                  one carries out, or null
     */
    record CachedStep(String action, String name, String value, Integer dayOffset, String expected) {

        StepRunner.Step asStep(LocalDate today) {
            String v = value;
            if ("date".equals(action) && dayOffset != null) {
                v = today.plusDays(dayOffset).format(DAY);
            }
            return new StepRunner.Step(action, name, v == null ? "" : v);
        }

        CachedStep expecting(String expectation) {
            return new CachedStep(action, name, value, dayOffset, expectation);
        }
    }

    /** The steps, or why there are none. */
    record Extracted(List<CachedStep> steps, String refusal) {
        boolean usable() {
            return refusal == null && !steps.isEmpty();
        }
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Look and nothing else: no step in a scenario. */
    private static final Set<String> SILENT = Set.of(
            "browser_snapshot", "browser_find", "browser_wait_for", "browser_console_messages",
            "browser_network_requests", "browser_take_screenshot",
            RunVerdict.TOOL, AiRunPrelude.LOGIN_TOOL, AiRunPrelude.MENU_TOOL, StepRunner.TOOL);

    private static final Set<String> CLICKABLE = Set.of(
            "button", "link", "tab", "menuitem", "checkbox", "radio", "treeitem", "switch");

    private CachedSteps() {
    }

    /* ------------------------------------------------------------------ */

    /**
     * @param calls    the whole tape of the walk
     * @param journal  the walk's do_steps batches
     * @param recorded the day the walk ran, for date offsets
     */
    static Extracted fromWalk(List<Call> calls, StepJournal journal, LocalDate recorded) {
        List<CachedStep> out = new ArrayList<>();
        List<StepJournal.Batch> batches = journal == null ? List.of() : journal.batches();
        String comboLabel = null;
        for (int i = 0; i < calls.size(); i++) {
            StepJournal.Batch batch = batchAt(batches, i);
            if (batch != null) {
                for (StepRunner.Step step : batch.done()) {
                    out.add(cached(step, recorded));
                }
                i = batch.to() - 1;
                comboLabel = null;
                continue;
            }
            Call call = calls.get(i);
            if (SILENT.contains(call.tool()) || !"OK".equals(call.outcome())) {
                continue;
            }
            JsonNode args = read(call.args());
            AriaSnapshot page = AriaSnapshot.of(pageBefore(calls, i));
            String target = text(args, "target");
            switch (call.tool()) {
                case "browser_click" -> {
                    String role = page.node(target).map(AriaSnapshot.Node::role).orElse("");
                    String caption = page.node(target).map(AriaSnapshot.Node::caption).orElse("").strip();
                    String combo = "combobox".equals(role) ? target : comboAround(page, target);
                    if (combo != null) {
                        // Opening a dropdown; the option click that follows is the step.
                        comboLabel = label(page, combo);
                        if (comboLabel.isBlank()) {
                            return refuse(call, "açılan listenin etiketi okunamadı");
                        }
                        continue;
                    }
                    if ("option".equals(role)) {
                        if (comboLabel == null || caption.isEmpty()) {
                            return refuse(call, "listeden bir seçenek seçildi ama hangi listeden olduğu okunamadı");
                        }
                        out.add(new CachedStep("select", comboLabel, caption, null, null));
                        comboLabel = null;
                        continue;
                    }
                    comboLabel = null;
                    if (page.insideRow(target) && !CLICKABLE_OUTSIDE_ROW.contains(role)) {
                        String row = page.rowIdentifier(target);
                        if (row.isBlank()) {
                            return refuse(call, "bir ızgara satırı seçildi ama satırı tanıtan hücre bulunamadı");
                        }
                        out.add(new CachedStep("row", row, null, null, null));
                        continue;
                    }
                    if (CLICKABLE.contains(role) && !caption.isEmpty()) {
                        out.add(new CachedStep("click", caption, null, null, null));
                        continue;
                    }
                    return refuse(call, "tıklanan öğenin adı okunamadı (" + (role.isEmpty() ? "?" : role) + ")");
                }
                case "browser_press_sequentially" -> {
                    if (comboLabel == null) {
                        return refuse(call, "odaktaki kutuya yazıldı ama hangi kutu olduğu okunamadı");
                    }
                    // Searching an open dropdown; the option click that follows is the step.
                }
                case "set_date" -> {
                    String label = label(page, target);
                    String date = text(args, "date");
                    if (label.isBlank() || date.isBlank()) {
                        return refuse(call, "tarih kutusunun etiketi okunamadı");
                    }
                    out.add(cached(new StepRunner.Step("date", label, date), recorded));
                    comboLabel = null;
                }
                case "browser_type" -> {
                    String label = label(page, target);
                    if (label.isBlank()) {
                        return refuse(call, "yazılan kutunun etiketi okunamadı");
                    }
                    out.add(new CachedStep("type", label, text(args, "text"), null, null));
                    comboLabel = null;
                }
                case "browser_select_option" -> {
                    String label = label(page, target);
                    JsonNode values = args == null ? null : args.get("values");
                    String value = values != null && values.isArray() && !values.isEmpty()
                            ? values.get(0).asText() : "";
                    if (label.isBlank() || value.isBlank()) {
                        return refuse(call, "seçilen listenin etiketi okunamadı");
                    }
                    out.add(new CachedStep("select", label, value, null, null));
                    comboLabel = null;
                }
                case "browser_press_key" -> {
                    if (comboLabel == null) {
                        out.add(new CachedStep("press", text(args, "key"), null, null, null));
                    }
                }
                default -> {
                    return refuse(call, call.tool() + " tekrar oynatılamıyor");
                }
            }
        }
        if (out.isEmpty()) {
            return new Extracted(List.of(), "yürüyüşte tekrar oynatılacak bir adım yok");
        }
        return new Extracted(out, null);
    }

    /** A row's cell can itself be a link or a button; those are clicks, not row picks. */
    private static final Set<String> CLICKABLE_OUTSIDE_ROW = Set.of("button", "link", "checkbox");

    /**
     * Each expectation the scenario wrote under a step, put after the cached
     * step that carries that scenario step out: the first one, from where the
     * previous match left off, whose name or value the scenario step quotes.
     *
     * @return the expectations no cached step could be matched to — checked
     *         on the last page instead, and reported as such
     */
    static List<String> align(List<CachedStep> steps, String scenario) {
        List<String> unmatched = new ArrayList<>();
        int from = 0;
        for (StepExpectations.Step wanted : StepExpectations.withExpectation(scenario)) {
            List<String> names = wanted.quoted().stream().map(ClaimCheck::compact).toList();
            int hit = -1;
            for (int k = from; k < steps.size() && hit < 0; k++) {
                CachedStep step = steps.get(k);
                if (names.contains(ClaimCheck.compact(step.name()))
                        || step.value() != null && names.contains(ClaimCheck.compact(step.value()))) {
                    hit = k;
                }
            }
            if (hit < 0) {
                unmatched.add(wanted.expected());
                continue;
            }
            steps.set(hit, steps.get(hit).expecting(wanted.expected()));
            from = hit + 1;
        }
        return unmatched;
    }

    /* ------------------------------------------------------------------ */

    static String toJson(List<CachedStep> steps) {
        try {
            return JSON.writeValueAsString(steps);
        } catch (Exception impossible) {
            return "[]";
        }
    }

    static List<CachedStep> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<CachedStep>>() { });
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** One line per step, as the console and the UI print it. */
    static String describe(CachedStep step) {
        StringBuilder line = new StringBuilder(step.action()).append(" \"").append(step.name()).append('"');
        if (step.value() != null && !step.value().isBlank()) {
            line.append(" = \"").append(step.value()).append('"');
        }
        if (step.dayOffset() != null) {
            line.append(" (bugün ").append(step.dayOffset() >= 0 ? "+" : "").append(step.dayOffset()).append(" gün)");
        }
        return line.toString();
    }

    /* ------------------------------------------------------------------ */

    private static CachedStep cached(StepRunner.Step step, LocalDate recorded) {
        String action = step.action().toLowerCase(java.util.Locale.ROOT);
        Integer offset = null;
        if ("date".equals(action)) {
            try {
                offset = (int) ChronoUnit.DAYS.between(recorded, LocalDate.parse(step.value().strip(), DAY));
            } catch (DateTimeParseException | NullPointerException notADay) {
                offset = null;
            }
        }
        return new CachedStep(action, step.name(), blankToNull(step.value()), offset, null);
    }

    private static StepJournal.Batch batchAt(List<StepJournal.Batch> batches, int index) {
        for (StepJournal.Batch batch : batches) {
            if (batch.from() == index) {
                return batch;
            }
        }
        return null;
    }

    /** The last whole page on the tape before entry {@code index}. */
    private static String pageBefore(List<Call> calls, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Call call = calls.get(i);
            if ("browser_snapshot".equals(call.tool()) && "OK".equals(call.outcome())
                    && (call.args() == null || !call.args().contains("target"))
                    && !AriaSnapshot.of(call.result()).isEmpty()) {
                return call.result();
            }
        }
        return null;
    }

    /** The Select2 combobox a clicked textbox sits in (combobox > textbox "Seçiniz"), or null. */
    private static String comboAround(AriaSnapshot page, String ref) {
        return page.node(ref)
                .filter(node -> "textbox".equals(node.role()) && node.parent() >= 0
                        && "combobox".equals(page.nodes().get(node.parent()).role()))
                .map(node -> page.nodes().get(node.parent()).ref())
                .orElse(null);
    }

    /** The label a step would name this control by: its own label, else its name. */
    private static String label(AriaSnapshot page, String ref) {
        String label = page.labelOf(ref);
        if (label.isBlank()) {
            label = page.ownerLabel(ref);
        }
        if (label.isBlank()) {
            label = page.nameOf(ref);
        }
        return label == null ? "" : label.strip();
    }

    private static Extracted refuse(Call call, String why) {
        return new Extracted(List.of(), "#" + call.seq() + " " + why);
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

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? "" : value.asText().strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
