package com.ykb.uplift.uptest.playwright.discovery;

import com.ykb.uplift.uptest.playwright.discovery.Discovered.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ykb.uplift.uptest.playwright.discovery.Discovered.describe;

/**
 * The draft catalog a scan produced, written out as YAML.
 *
 * Split out of {@link CatalogDiscovery} unchanged; CatalogDiscovery keeps the
 * public toYaml() and hands its state here.
 */
final class CatalogYaml {

    private final Map<String, Field> catalog;
    private final Map<String, List<Effect>> effects;
    private final Map<String, String> unrecognised;
    private final Map<String, Integer> buttonScreens;
    private final int screensScanned;
    private final StringBuilder debugLog;

    CatalogYaml(Map<String, Field> catalog, Map<String, List<Effect>> effects,
                Map<String, String> unrecognised, Map<String, Integer> buttonScreens,
                int screensScanned, StringBuilder debugLog) {
        this.catalog = catalog;
        this.effects = effects;
        this.unrecognised = unrecognised;
        this.buttonScreens = buttonScreens;
        this.screensScanned = screensScanned;
        this.debugLog = debugLog;
    }

    /* =============================== output =============================== */

    /**
     * The observed half of a catalog: fields and buttons.
     *
     * No app/flow/navigation header here — this class only ever sees the screen
     * it is standing on, and cannot know which application it logged into or
     * the menu path that got it here. The orchestrator asked for all three and
     * writes them itself (CatalogDiscoveryService), so the analyst is never
     * asked to retype what the form already collected.
     */
    String toYaml() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("fields:\n");

        // catalog is insertion-ordered and a field is inserted the first time it
        // is seen, so the screen number only ever climbs: a comment whenever it
        // changes is enough to group the flat list SCHEMA.md requires.
        int lastScreen = 0;
        for (Field field : catalog.values()) {
            if (field.firstSeenScreen != lastScreen) {
                lastScreen = field.firstSeenScreen;
                yaml.append("  # --- ekran ").append(lastScreen).append(" ---\n");
            }
            // What this field set off, written above it so the ordering the
            // screen requires is visible to whoever reads the catalog next —
            // analyst or model.
            List<Effect> caused = effects.get(field.label);
            if (caused != null) {
                yaml.append("    # ").append(describe(field.label, caused)).append('\n');
            }

            yaml.append("  - label: \"").append(escape(field.label)).append("\"\n");
            yaml.append("    type: ").append(field.type.schemaName).append('\n');
            if (field.readonly) {
                yaml.append("    readonly: true\n");
            }
            if (field.verifiable) {
                yaml.append("    verifiable: true\n");
            }
            appendLimits(yaml, field);
            if (field.type == Type.DROPDOWN) {
                appendList(yaml, "    ", "options", field.choices);
            } else if (field.type == Type.ACCOUNT_PICKER) {
                appendList(yaml, "    ", "accounts", field.choices);
            } else if (field.type == Type.UNSUPPORTED_V3) {
                yaml.append("    # UNSUPPORTED_V3: Select2 v3 picker, no vocabulary step drives this\n");
            }
        }

        appendButtons(yaml);
        appendUnrecognised(yaml);
        yaml.append(debugLog);
        return yaml.toString();
    }

    /**
     * What the field's own attributes said it accepts.
     *
     * Only what was actually read: an absent key means the screen did not say,
     * and the generator treats that differently from a limit of zero. Writing
     * {@code max_length: 0} for a box with no maxlength would be the kind of
     * confident wrong answer this class exists to avoid.
     *
     * This is the one part of a catalog only the DOM scan can produce. The MCP
     * agent works from an accessibility snapshot, which carries no attributes at
     * all, so an agent-written catalog is silent here and the planner says so
     * rather than inventing a bound. Same shape as the Select2 v3-versus-v4 split
     * — two producers, opposite blind spots, both kept.
     */
    private static void appendLimits(StringBuilder yaml, Field field) {
        if (!field.limits.known()) {
            return;
        }
        if (field.limits.maxLength() != null) {
            yaml.append("    max_length: ").append(field.limits.maxLength()).append('\n');
        }
        if (field.limits.min() != null) {
            yaml.append("    min: ").append(field.limits.min()).append('\n');
        }
        if (field.limits.max() != null) {
            yaml.append("    max: ").append(field.limits.max()).append('\n');
        }
        if (field.limits.numeric()) {
            yaml.append("    numeric: true\n");
        }
    }

    /**
     * Fields the scan saw but could not type. A comment, not a catalog entry:
     * no vocabulary step drives a radio outside a grid or a textarea, so
     * listing one as a field would produce a scenario that fails in the
     * browser. Listing it as a comment tells whoever reads the draft that the
     * screen has more on it than the catalog claims.
     *
     * Checkboxes used to be in here and are not any more: `check {string}`
     * drives them, so they are catalogued as `checkbox` like any other field.
     */
    private void appendUnrecognised(StringBuilder yaml) {
        if (unrecognised.isEmpty()) {
            return;
        }
        yaml.append("\n# taninmayan alanlar - katalogda yok, elle bakin:\n");
        unrecognised.forEach((label, control) -> {
            yaml.append("#   \"").append(escape(label)).append("\" -> ").append(control).append('\n');
            System.out.println("[uptest] taninmayan alan: \"" + label + "\" -> " + control);
        });
    }

    /**
     * Flow buttons in the catalog, the site menu in a comment beside it.
     *
     * A button on every single screen scanned is chrome — the top menu rides
     * along on all of them — while "Devam" and "Onay" belong to one screen
     * each. Sending all 25 to the model would spend prompt on 21 buttons no
     * scenario for this flow will ever press.
     *
     * With only one screen scanned there is nothing to compare, so everything
     * is listed: a wrong guess there would quietly drop the flow's real
     * buttons.
     */
    private void appendButtons(StringBuilder yaml) {
        List<String> flowButtons = new ArrayList<>();
        List<String> chrome = new ArrayList<>();

        buttonScreens.forEach((name, screens) -> {
            boolean onEveryScreen = screensScanned > 1 && screens == screensScanned;
            (onEveryScreen ? chrome : flowButtons).add(name);
        });

        appendList(yaml, "", "buttons", flowButtons);
        if (!chrome.isEmpty()) {
            // To the log, not the catalog: what was excluded is worth seeing
            // once, but the catalog is pasted into every prompt and a comment
            // costs the same tokens as a key.
            System.out.println("[uptest] katalog disi birakilan site menusu: " + String.join(", ", chrome));
        }
    }

    private static void appendList(StringBuilder yaml, String indent, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        yaml.append(indent).append(key).append(":\n");
        for (String v : values) {
            yaml.append(indent).append("  - \"").append(escape(v)).append("\"\n");
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
