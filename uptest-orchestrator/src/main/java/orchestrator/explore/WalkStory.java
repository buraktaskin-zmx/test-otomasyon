package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import orchestrator.explore.TapeRenderer.Call;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "AI ile çalıştır"'s walk, told step by step from the TAPE: logged in, this
 * screen opened, this was clicked, this is what the screen then showed.
 *
 * <p><b>Why from the tape and not from the agent.</b> The tape is what
 * happened; the agent's account is a claim (ClaimCheck exists because one
 * agent reported a click it never made). Built in code it costs nothing, reads
 * the same on every run, and tells a cached replay exactly like an agent walk.
 *
 * <p>The shape is a tree, as an analyst would tell it:
 * <pre>
 * ✓ Giriş yapıldı
 * ✓ NAKİT YÖNETİMİ › Firma Fatura İşlemleri açıldı
 *   ▸ Firma Fatura İşlemleri
 *     ✓ "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seçildi
 *     ✓ "Fatura İptal Et" bağlantısına tıklandı
 *   ▸ Fatura İptal  (yeni ekran)
 *     ✗ "Evet" reddedildi: …
 * </pre>
 *
 * <p>What it says it SAW is only what a page states and code can read without
 * judging: which screen it is (its heading), a dialog or alert that opened, a
 * "… giriniz." line, how many rows a grid has. Whether that was RIGHT is the
 * verdict's business, not this story's.
 */
final class WalkStory {

    /** One line of the story. kind: ok, fail, refused, screen, seen. */
    record Line(int depth, String kind, String text) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lines past this are summarised in one; a looping walk must not bury the AI ÖZET. */
    static final int MAX_LINES = 80;

    private static final Set<String> DIALOGS = Set.of("dialog", "alertdialog", "alert");

    private final List<Line> lines = new ArrayList<>();
    private final Set<String> screensSeen = new HashSet<>();
    /** The screen the prelude opened from the menu, when it did. */
    private String menuScreen;
    private String screen;
    private AriaSnapshot page;
    private Set<String> saying = new LinkedHashSet<>();
    private String comboLabel;
    private boolean afterAction;

    private WalkStory() {
    }

    static List<Line> of(List<Call> calls) {
        WalkStory story = new WalkStory();
        for (Call call : calls) {
            story.read(call);
        }
        return story.capped();
    }

    /** As the answer carries it. */
    static List<Map<String, Object>> toJson(List<Line> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Line line : lines) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("depth", line.depth());
            one.put("kind", line.kind());
            one.put("text", line.text());
            out.add(one);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */

    private void read(Call call) {
        String tool = call.tool();
        boolean ok = "OK".equals(call.outcome());
        JsonNode args = read(call.args());
        switch (tool) {
            case AiRunPrelude.LOGIN_TOOL -> add(0, ok ? "ok" : "fail", ok
                    ? "Giriş yapıldı" + (text(args, "cif").isEmpty() ? "" : " (CIF " + text(args, "cif") + ")")
                    : "Giriş koddan yapılamadı: " + firstLine(call.result()));
            case AiRunPrelude.MENU_TOOL -> {
                menuScreen = ok ? text(args, "screen") : null;
                add(0, ok ? "ok" : "fail", ok
                    ? text(args, "mainMenu") + " › " + text(args, "screen") + " açıldı"
                    : "Menü koddan açılamadı: " + firstLine(call.result()));
            }
            case "browser_snapshot" -> {
                if (ok && (call.args() == null || !call.args().contains("target"))) {
                    look(AriaSnapshot.of(call.result()));
                }
            }
            case "browser_navigate" -> act(call, ok, "\"" + text(args, "url") + "\" adresine gidildi");
            case "browser_click" -> click(call, ok, args);
            case "set_date" -> {
                String label = label(text(args, "target"), text(args, "element"));
                boolean took = ok && DateTool.wasSet(call.result());
                act(call, took, "\"" + label + "\" tarihi " + text(args, "date") + " olarak girildi");
            }
            case "browser_type" -> act(call, ok, "\"" + label(text(args, "target"), text(args, "element"))
                    + "\" kutusuna \"" + text(args, "text") + "\" yazıldı");
            case "browser_select_option" -> {
                JsonNode values = args == null ? null : args.get("values");
                String value = values != null && values.isArray() && !values.isEmpty() ? values.get(0).asText() : "";
                act(call, ok, "\"" + label(text(args, "target"), text(args, "element")) + "\" listesinden \""
                        + value + "\" seçildi");
            }
            case "browser_press_sequentially" -> {
                if (comboLabel == null) {
                    act(call, ok, "\"" + text(args, "text") + "\" yazıldı");
                }
            }
            case "browser_press_key" -> act(call, ok, text(args, "key") + " tuşuna basıldı");
            case "browser_handle_dialog" -> act(call, ok, "açılan pencere "
                    + ("true".equals(text(args, "accept")) ? "kabul edildi" : "kapatıldı"));
            default -> {
                // do_steps leaves its sub-steps on the tape under their own tools;
                // report_result and the looking tools change nothing.
            }
        }
    }

    private void click(Call call, boolean ok, JsonNode args) {
        String target = text(args, "target");
        String element = text(args, "element");
        String role = page == null ? "" : page.node(target).map(AriaSnapshot.Node::role).orElse("");
        String caption = page == null ? "" : page.node(target).map(AriaSnapshot.Node::caption).orElse("").strip();
        if (ok && ("combobox".equals(role) || insideCombo(target))) {
            // Opening a list; the option clicked next is the step worth a line.
            comboLabel = label(target, element);
            return;
        }
        if ("option".equals(role)) {
            String list = comboLabel == null ? "" : "\"" + comboLabel + "\" listesinden ";
            comboLabel = null;
            act(call, ok, list + "\"" + (caption.isEmpty() ? element : caption) + "\" seçildi");
            return;
        }
        comboLabel = null;
        if (page != null && page.insideRow(target) && !Set.of("button", "link", "checkbox").contains(role)) {
            String row = page.rowIdentifier(target);
            act(call, ok, "\"" + (row.isEmpty() ? element : row) + "\" satırı seçildi");
            return;
        }
        String name = !caption.isEmpty() ? caption : element.isEmpty() ? target : element;
        act(call, ok, "\"" + name + "\" " + kindOf(role) + " tıklandı");
    }

    private void act(Call call, boolean ok, String done) {
        if ("REFUSED".equals(call.outcome())) {
            add(depth(), "refused", stripVerb(done) + " — reddedildi: " + firstLine(call.result()));
            return;
        }
        if (!ok) {
            add(depth(), "fail", stripVerb(done) + " — yapılamadı: " + firstLine(call.result()));
            return;
        }
        add(depth(), "ok", done);
        afterAction = true;
    }

    /** A page arrived: maybe a new screen, maybe something new on this one. */
    private void look(AriaSnapshot next) {
        if (next.isEmpty()) {
            return;
        }
        String heading = headingOf(next);
        Set<String> now = sayingOn(next);
        if (heading != null && !heading.equals(screen)) {
            boolean back = screensSeen.contains(heading);
            boolean first = screen == null;
            screen = heading;
            screensSeen.add(heading);
            add(1, "screen", heading + (first ? "" : back ? " (geri dönüldü)" : " (yeni ekran)"));
            String rows = rowsOf(next);
            if (rows != null) {
                add(2, "seen", "görüldü: " + rows);
            }
            now.forEach(message -> add(2, "seen", "görüldü: " + message));
        } else {
            Set<String> fresh = new LinkedHashSet<>(now);
            fresh.removeAll(saying);
            int depth = afterAction ? 3 : 2;
            String rows = rowsOf(next);
            if (rows != null && !rows.equals(page == null ? null : rowsOf(page))) {
                // The grid filled or changed: a firm picked, a search run.
                add(depth, "seen", "görüldü: " + rows);
            }
            fresh.forEach(message -> add(depth, "seen", "görüldü: " + message));
        }
        saying = now;
        page = next;
        afterAction = false;
    }

    /* ------------------------------------------------------------------ */

    /**
     * The screen's title. The screen has not changed while its heading is
     * still on the page — an inner panel's title ("İŞLEMLER") is not a new
     * screen. When it has gone, the new screen is named by the first heading
     * the previous page did not have, so a heading every page carries (the
     * bank's frame) never names one.
     */
    private String headingOf(AriaSnapshot snap) {
        List<String> headings = headings(snap);
        if (headings.isEmpty()) {
            return null;
        }
        if (screen == null) {
            return menuScreen != null && headings.contains(menuScreen) ? menuScreen : headings.get(0);
        }
        if (headings.contains(screen)) {
            return screen;
        }
        List<String> before = page == null ? List.of() : headings(page);
        return headings.stream().filter(h -> !before.contains(h)).findFirst().orElse(headings.get(0));
    }

    private static List<String> headings(AriaSnapshot snap) {
        List<String> out = new ArrayList<>();
        for (AriaSnapshot.Node node : snap.nodes()) {
            if ("heading".equals(node.role()) && !node.caption().isBlank()) {
                out.add(node.caption().strip());
            }
        }
        return out;
    }

    /** Dialogs and alerts with their first line of text, and the "… giriniz." lines. */
    private static Set<String> sayingOn(AriaSnapshot snap) {
        Set<String> out = new LinkedHashSet<>();
        for (AriaSnapshot.Node node : snap.nodes()) {
            if (DIALOGS.contains(node.role())) {
                String inside = snap.childrenOf(node.ref()).stream().map(AriaSnapshot.Node::caption)
                        .filter(text -> !text.isBlank()).findFirst().orElse("");
                String title = node.caption().strip();
                out.add((node.role().equals("dialog") ? "pencere " : "uyarı ")
                        + (title.isEmpty() ? "" : "\"" + title + "\"")
                        + (inside.isEmpty() ? "" : (title.isEmpty() ? "" : ": ") + inside.strip()));
                continue;
            }
            String folded = TapeRenderer.fold(node.caption());
            if ((folded.endsWith("giriniz.") || folded.endsWith("seciniz.")) && node.caption().contains(" ")) {
                out.add("mesaj \"" + node.caption().strip() + "\"");
            }
        }
        return out;
    }

    /** "ızgarada 12 satır", counting data rows only (a row of column headers is not one). */
    private static String rowsOf(AriaSnapshot snap) {
        int rows = 0;
        List<AriaSnapshot.Node> nodes = snap.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            if (!"row".equals(nodes.get(i).role())) {
                continue;
            }
            boolean header = false;
            for (int k = i + 1; k < nodes.size() && nodes.get(k).indent() > nodes.get(i).indent(); k++) {
                if ("columnheader".equals(nodes.get(k).role())) {
                    header = true;
                    break;
                }
            }
            if (!header) {
                rows++;
            }
        }
        return rows == 0 ? null : "ızgarada " + rows + " satır";
    }

    private boolean insideCombo(String ref) {
        return page != null && page.node(ref).map(node -> node.parent() >= 0
                && "combobox".equals(page.nodes().get(node.parent()).role())).orElse(false);
    }

    private String label(String ref, String element) {
        if (page != null && !ref.isEmpty()) {
            String target = ref;
            if (insideCombo(ref)) {
                target = page.node(ref).map(node -> page.nodes().get(node.parent()).ref()).orElse(ref);
            }
            String found = page.labelOf(target);
            if (found.isBlank()) {
                found = page.ownerLabel(target);
            }
            if (!found.isBlank()) {
                return found.strip();
            }
        }
        return element.isEmpty() ? ref : element;
    }

    private static String kindOf(String role) {
        return switch (role) {
            case "button" -> "düğmesine";
            case "link" -> "bağlantısına";
            case "tab" -> "sekmesine";
            case "checkbox" -> "kutusuna";
            case "radio" -> "seçeneğine";
            case "menuitem" -> "menüsüne";
            default -> "öğesine";
        };
    }

    /** "… tıklandı" → "…", for a line that says the action did NOT happen. */
    private static String stripVerb(String done) {
        for (String verb : List.of(" tıklandı", " seçildi", " yazıldı", " girildi", " gidildi", " basıldı",
                " kabul edildi", " kapatıldı")) {
            if (done.endsWith(verb)) {
                return done.substring(0, done.length() - verb.length());
            }
        }
        return done;
    }

    private int depth() {
        return screen == null ? 1 : 2;
    }

    private void add(int depth, String kind, String text) {
        lines.add(new Line(depth, kind, text));
    }

    private List<Line> capped() {
        if (lines.size() <= MAX_LINES) {
            return List.copyOf(lines);
        }
        List<Line> out = new ArrayList<>(lines.subList(0, MAX_LINES - 1));
        out.add(new Line(0, "seen", "… " + (lines.size() - MAX_LINES + 1) + " satır daha (tamamı bantta)"));
        return out;
    }

    private static String firstLine(String text) {
        String plain = text == null ? "" : LeanResults.unwrap(text).replace("### Error", "").strip();
        String first = plain.lines().filter(line -> !line.isBlank()).findFirst().orElse("").strip();
        for (String prefix : List.of("Error: ", "REFUSED: ")) {
            if (first.startsWith(prefix)) {
                first = first.substring(prefix.length());
            }
        }
        return first.length() > 140 ? first.substring(0, 140) + "…" : first;
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
}
