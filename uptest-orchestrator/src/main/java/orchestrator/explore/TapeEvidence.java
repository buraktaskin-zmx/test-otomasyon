package orchestrator.explore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the model is shown of a walk: short, readable, and nothing invented.
 *
 * <p>A tape is 150 KB — almost all of it full-page snapshots — and handing it
 * to the model whole buys nothing but cost and a lost needle. What the model
 * needs to write the scenario is much smaller and is all here:
 * <ul>
 *   <li>every action, one line each: what the agent SAID it was touching, the
 *       Playwright line the server actually ran, the value, and whether the
 *       application took it. Failed and repeated attempts are kept and marked,
 *       because telling them from the real path is the model's job;</li>
 *   <li>the field labels the screens printed, in the order they printed them —
 *       the only spellings a step may use;</li>
 *   <li>what dialogs and validation messages said, so a refused submit is
 *       recognisable as one;</li>
 *   <li>what the last screen showed, label by label, for the THEN.</li>
 * </ul>
 *
 * <p>The login is left out: it is the one part of the walk a scenario does not
 * replay, and it carries the password.
 */
public final class TapeEvidence {

    /** Reads nothing, changes nothing — only their failures are worth a line. */
    private static final Set<String> LOOKING = Set.of(
            "browser_snapshot", "browser_find", "browser_wait_for", "browser_take_screenshot",
            "browser_console_messages", "browser_network_requests");

    private static final Pattern CODE = Pattern.compile("await page[^\\\\\\n]*?;");
    private static final int LINE_CAP = 260;
    private static final int TOTAL_CAP = 14_000;

    private TapeEvidence() {
    }

    /** The digest the prompt carries. */
    public static String digest(List<TapeRenderer.Call> calls, String mainMenu) {
        int start = start(calls, mainMenu);
        StringBuilder out = new StringBuilder();

        out.append("AJANIN ADIMLARI (giriş hariç, sırayla; ✗ = uygulama/araç reddetti):\n");
        int length = 0;
        for (int i = start; i < calls.size(); i++) {
            String line = describe(calls.get(i));
            if (line == null) {
                continue;
            }
            if (length + line.length() > TOTAL_CAP) {
                out.append("  … (iz burada kesildi, devamı çok uzun)\n");
                break;
            }
            out.append("  ").append(line).append('\n');
            length += line.length();
        }

        List<AriaSnapshot> snapshots = new ArrayList<>();
        for (int i = start; i < calls.size(); i++) {
            AriaSnapshot parsed = AriaSnapshot.of(calls.get(i).result());
            if (!parsed.isEmpty()) {
                snapshots.add(parsed);
            }
        }

        Set<String> labels = new LinkedHashSet<>();
        for (AriaSnapshot snapshot : snapshots) {
            labels.addAll(snapshot.controlLabels());
        }
        out.append("\nEKRANLARDA GÖRÜLEN ALAN ETİKETLERİ (ekrandaki yazımıyla, sırasıyla):\n");
        if (labels.isEmpty()) {
            out.append("  (okunamadı)\n");
        }
        labels.forEach(label -> out.append("  \"").append(label).append("\"\n"));

        Set<String> messages = new LinkedHashSet<>();
        for (AriaSnapshot snapshot : snapshots) {
            messages.addAll(messagesIn(snapshot));
        }
        if (!messages.isEmpty()) {
            out.append("\nEKRANDA GÖRÜLEN DİYALOG / UYARI METİNLERİ:\n");
            messages.forEach(message -> out.append("  \"").append(message).append("\"\n"));
        }

        Map<String, String> last = lastScreen(calls);
        if (!last.isEmpty()) {
            out.append("\nSON EKRANDA OKUNAN ETİKET → DEĞER:\n");
            last.forEach((label, value) ->
                    out.append("  \"").append(label).append("\" → \"").append(value).append("\"\n"));
        }
        return out.toString();
    }

    /** One line for one call, or null when the call is not worth a line. */
    static String describe(TapeRenderer.Call call) {
        String tool = call.tool();
        boolean ok = "OK".equals(call.outcome());
        if (LOOKING.contains(tool)) {
            return null;
        }
        StringBuilder line = new StringBuilder("#").append(call.seq()).append(' ').append(tool);
        String element = arg(call, "element");
        if (!element.isBlank()) {
            line.append(" | ajan: \"").append(element).append('"');
        }
        for (String key : List.of("text", "date", "key", "values")) {
            String value = arg(call, key);
            if (!value.isBlank()) {
                line.append(" | ").append(key).append(": \"").append(value).append('"');
            }
        }
        String code = code(call.result());
        if (!code.isBlank()) {
            line.append(" | çalışan: ").append(code);
        }
        if (!ok) {
            line.append(" | ✗ ").append(firstErrorLine(call.result()));
        } else if ("set_date".equals(tool) && call.result() != null && !call.result().startsWith("Set to")) {
            line.append(" | ✗ ").append(oneLine(call.result()));
        }
        String text = line.toString();
        return text.length() > LINE_CAP ? text.substring(0, LINE_CAP) + "…" : text;
    }

    /** Where the scenario starts: the last click on the main menu. */
    private static int start(List<TapeRenderer.Call> calls, String mainMenu) {
        if (mainMenu == null || mainMenu.isBlank()) {
            return 0;
        }
        String menu = TapeRenderer.fold(mainMenu);
        int last = 0;
        for (int i = 0; i < calls.size(); i++) {
            TapeRenderer.Call call = calls.get(i);
            if ("browser_click".equals(call.tool()) && "OK".equals(call.outcome())
                    && TapeRenderer.fold(code(call.result())).contains("'" + menu + "'")) {
                last = i;
            }
        }
        return last;
    }

    /** Text inside a dialog, and the "… giriniz." lines a form paints under a field. */
    private static List<String> messagesIn(AriaSnapshot snapshot) {
        List<String> found = new ArrayList<>();
        List<AriaSnapshot.Node> nodes = snapshot.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            AriaSnapshot.Node node = nodes.get(i);
            String caption = node.caption() == null ? "" : node.caption().trim();
            if (caption.isBlank() || caption.length() > 200) {
                continue;
            }
            String folded = TapeRenderer.fold(caption);
            boolean validation = (folded.endsWith("giriniz.") || folded.endsWith("seciniz."))
                    && caption.contains(" ");
            if (validation || insideDialog(nodes, i)) {
                found.add(caption);
            }
        }
        return found;
    }

    private static boolean insideDialog(List<AriaSnapshot.Node> nodes, int index) {
        int at = nodes.get(index).parent();
        while (at >= 0) {
            String role = nodes.get(at).role();
            if ("dialog".equals(role) || "alertdialog".equals(role)) {
                return true;
            }
            at = nodes.get(at).parent();
        }
        return false;
    }

    /** The label/value pairs of the last full snapshot. */
    private static Map<String, String> lastScreen(List<TapeRenderer.Call> calls) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            TapeRenderer.Call call = calls.get(i);
            if (!"browser_snapshot".equals(call.tool()) || !"OK".equals(call.outcome())
                    || (call.args() != null && call.args().contains("target"))) {
                continue;
            }
            AriaSnapshot parsed = AriaSnapshot.of(call.result());
            if (!parsed.isEmpty()) {
                return new LinkedHashMap<>(parsed.labelledValues());
            }
        }
        return Map.of();
    }

    static String arg(TapeRenderer.Call call, String name) {
        Matcher match = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|\\[[^]]*])")
                .matcher(call.args() == null ? "" : call.args());
        if (!match.find()) {
            return "";
        }
        return match.group(2) != null ? match.group(2) : match.group(1);
    }

    private static String code(String result) {
        if (result == null) {
            return "";
        }
        Matcher match = CODE.matcher(result);
        return match.find() ? match.group() : "";
    }

    private static String firstErrorLine(String result) {
        if (result == null) {
            return "hata";
        }
        int at = result.indexOf("Error");
        String from = at < 0 ? result : result.substring(at);
        return oneLine(from);
    }

    private static String oneLine(String text) {
        String flat = text.replace("\\n", " ").replaceAll("\\s+", " ").trim();
        return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
    }
}
