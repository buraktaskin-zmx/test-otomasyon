package orchestrator.catalog;

import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a synthetic "login, navigate, discover" feature and dispatches it
 * exactly like a normal run — reusing {@link ExecutorPool} and the executor's
 * existing /cucumber/start-status-artifacts trio, so no new browser-lifecycle
 * or transport code exists anywhere.
 *
 * Deliberately bypasses {@link orchestrator.validation.ScenarioValidator}:
 * "discover screen catalog" is excluded from vocabulary.json on purpose (see
 * VocabularyExtractor.DISCOVERY_PACKAGE), so validating against it would
 * always fail. Safe to skip here because this class, not the analyst, writes
 * every step line; only the quoted values are analyst input, and those are
 * rejected outright if they contain a quote (see {@link #quoted}).
 *
 * Kept separate from {@link orchestrator.run.RunService} so discovery runs
 * never pollute the analyst-facing run history.
 */
@Service
public class CatalogDiscoveryService {

    private static final String OUTPUT_FILE = "catalog-draft.yaml";
    private static final Set<String> TERMINAL = Set.of("DONE", "FAILED", "CANCELED");

    /**
     * One screen of the flow: what to type into it, then which button leaves it.
     *
     * @param values what to type where, by field label. The analyst supplies
     *               business values only — which verb drives each one
     *               (enter / enter amount / select / select account) is read off
     *               the DOM by the scan that precedes the fill. May be empty for
     *               a screen that is only confirmed, not filled.
     * @param button the button that advances to the next screen. Required: the
     *               scan after it is what makes the next screen discoverable.
     */
    public record Stage(Map<String, String> values, String button) {
    }

    /**
     * @param stages the flow, screen by screen. N stages produce N+1 scans: the
     *               entry screen, then one after each button. An empty list is
     *               the read-only case — scan the entry screen and stop.
     */
    public record DiscoverRequest(
            String app, String cif, String userid, String mainMenu, String subMenu,
            String entryRow, String entryButton,
            List<Stage> stages) {
    }

    public record StartOutcome(UUID runId, String executor, boolean accepted) {
    }

    private record DiscoveryRun(UUID id, String executor, DiscoverRequest request) {
    }

    private final ExecutorPool pool;
    private final ConcurrentHashMap<UUID, DiscoveryRun> runs = new ConcurrentHashMap<>();

    public CatalogDiscoveryService(ExecutorPool pool) {
        this.pool = pool;
    }

    public StartOutcome start(DiscoverRequest request) {
        String feature = buildFeature(request);
        UUID runId = UUID.randomUUID();

        Optional<ExecutorClient> executor = pool.dispatch(runId, feature);
        if (executor.isEmpty()) {
            return new StartOutcome(null, null, false);
        }

        runs.put(runId, new DiscoveryRun(runId, executor.get().name(), request));
        return new StartOutcome(runId, executor.get().name(), true);
    }

    /**
     * The executor's run status, plus the discovered YAML once the run is
     * DONE. A run that finished without ever reaching "discover screen
     * catalog" (e.g. login failed, navigation failed) has no artifact to
     * fetch — reported as {@code catalogError}, never guessed at.
     */
    public Optional<Map<String, Object>> status(UUID runId) {
        DiscoveryRun record = runs.get(runId);
        if (record == null) {
            return Optional.empty();
        }

        Optional<ExecutorClient> executor = pool.byName(record.executor());
        if (executor.isEmpty()) {
            return Optional.of(Map.of("status", "UNREACHABLE"));
        }

        Map<String, Object> status = executor.get().status(runId);
        if (status == null) {
            return Optional.of(Map.of("status", "UNREACHABLE"));
        }

        Map<String, Object> view = new LinkedHashMap<>(status);
        if (TERMINAL.contains(String.valueOf(status.get("status")))) {
            attachCatalog(executor.get(), runId, record.request(), view);
        }
        return Optional.of(view);
    }

    private void attachCatalog(ExecutorClient executor, UUID runId, DiscoverRequest request,
                               Map<String, Object> view) {
        ResponseEntity<byte[]> artifact = executor.artifact(runId, OUTPUT_FILE);
        if (artifact != null && artifact.getStatusCode().is2xxSuccessful() && artifact.getBody() != null) {
            String observed = new String(artifact.getBody(), StandardCharsets.UTF_8);
            view.put("catalogYaml", header(request) + observed);
        } else {
            view.put("catalogError",
                    OUTPUT_FILE + " not found — the run likely failed before reaching " +
                            "\"discover screen catalog\" (check the login/navigate steps' status above)");
        }
    }

    /**
     * app, flow and navigation, written from what the analyst already typed
     * into the discovery form.
     *
     * The executor cannot supply these — it only ever sees the screen it is
     * standing on, not which application it logged into or the menu path that
     * led there. Asking the analyst to retype them into the draft afterwards
     * was making a directly usable catalog into a fill-in-the-blanks exercise.
     */
    private static String header(DiscoverRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("app: ").append("NGI".equalsIgnoreCase(r.app()) ? "NGI" : "NGC").append('\n');
        sb.append("flow: ").append(flowName(r)).append('\n');
        sb.append("navigation:\n");
        sb.append("  main_menu: \"").append(r.mainMenu().trim()).append("\"\n");
        sb.append("  sub_menu: \"").append(r.subMenu().trim()).append("\"\n");
        // Ordered as the flow runs them, which is also the order SCHEMA.md
        // documents: the row is picked, then the button opens the form.
        if (r.entryRow() != null && !r.entryRow().isBlank()) {
            sb.append("  entry_row: \"").append(r.entryRow().trim()).append("\"\n");
        }
        if (r.entryButton() != null && !r.entryButton().isBlank()) {
            sb.append("  entry_button: \"").append(r.entryButton().trim()).append("\"\n");
        }
        return sb.toString();
    }

    /** The entry button names the flow when there is one; the sub-menu otherwise. */
    private static String flowName(DiscoverRequest r) {
        return (r.entryButton() != null && !r.entryButton().isBlank())
                ? r.entryButton().trim()
                : r.subMenu().trim();
    }

    /**
     * Every value the analyst supplies lands inside a Gherkin {string}
     * literal. Gherkin has no escape for an embedded double quote there, so
     * rather than silently corrupt the feature, refuse up front.
     */
    private static String quoted(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains("\"")) {
            throw new IllegalArgumentException("Value must not contain a double quote: " + value);
        }
        return value;
    }

    /** A pipe would end the cell and shift every column after it. */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains("|")) {
            throw new IllegalArgumentException("Value must not contain a pipe character: " + value);
        }
        return value.trim();
    }

    /**
     * Scan, fill, advance, scan again — one Gherkin scenario covering the whole
     * flow, built from steps this class writes rather than anything the analyst
     * typed as free text.
     */
    private static String buildFeature(DiscoverRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Feature: Katalog kesfi\n\n");
        sb.append("  Scenario: Ekrani kesfet\n");

        if ("NGI".equalsIgnoreCase(r.app())) {
            sb.append("    Given login to ngi with cif \"").append(quoted(r.cif())).append("\"\n");
        } else {
            sb.append("    Given login with cif \"").append(quoted(r.cif()))
                    .append("\" and userid \"").append(quoted(r.userid())).append("\"\n");
        }

        sb.append("    And navigate to \"").append(quoted(r.mainMenu()))
                .append("\" \"").append(quoted(r.subMenu())).append("\"\n");

        // Before the button, never after: on Online DBS İşlemleri the sub-menu
        // opens a list of institutions and "Fatura Oluştur" builds the form for
        // whichever row is selected.
        if (r.entryRow() != null && !r.entryRow().isBlank()) {
            sb.append("    And select row \"").append(quoted(r.entryRow())).append("\"\n");
        }

        if (r.entryButton() != null && !r.entryButton().isBlank()) {
            sb.append("    And click \"").append(quoted(r.entryButton())).append("\"\n");
        }

        // The entry screen is always scanned; every stage then fills it, leaves
        // it, and the scan after the button makes the next screen discoverable.
        sb.append("    Then discover screen catalog\n");

        if (r.stages() != null) {
            for (Stage stage : r.stages()) {
                if (stage == null) {
                    continue;
                }
                if (stage.values() != null && !stage.values().isEmpty()) {
                    sb.append("    And fill discovered fields:\n");
                    for (Map.Entry<String, String> entry : stage.values().entrySet()) {
                        sb.append("      | ").append(cell(entry.getKey()))
                                .append(" | ").append(cell(entry.getValue())).append(" |\n");
                    }
                }
                sb.append("    And click \"").append(quoted(stage.button().trim())).append("\"\n");
                sb.append("    Then discover screen catalog\n");
            }
        }

        return sb.toString();
    }
}
