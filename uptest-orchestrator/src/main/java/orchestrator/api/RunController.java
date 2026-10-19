package orchestrator.api;

import jakarta.servlet.http.HttpServletRequest;
import orchestrator.run.RunService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    /**
     * {@code app} and {@code flow} name the catalog this scenario was written
     * against, and {@code intent} is what the analyst asked for. All three are
     * optional — an older client sending only the feature still works, and
     * gets the grammar-only check it always got.
     *
     * Sending them is what lets the run gate be as strict as the generator.
     * Without them a draft the generator had already rejected starts anyway,
     * because the catalog checks cannot run without a catalog.
     */
    /**
     * {@code cif} and {@code userid} are the login the analyst PICKED from the
     * saved test users, not one typed into the scenario. The orchestrator
     * writes the step — see {@link RunService#start(String, String, String,
     * String, String, String, String)} for why that belongs on this side.
     *
     * They are optional like the rest: a scenario carrying its own login still
     * runs exactly as it did. They are also the one pair here that must never
     * be logged or echoed back — the response below returns an id, an executor
     * name and a step count, and nothing derived from the request body.
     */
    public record StartRunRequest(String feature, String app, String flow, String intent,
                                  String product, String cif, String userid) {
    }

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    /**
     * 202 with the run id when a free executor took it.
     *
     * 422 when the scenario would not run (with the per-line errors), 503 when
     * every executor is busy or down. Never queues silently: an analyst waiting
     * on an invisible run is worse than a clear refusal.
     */
    @PostMapping
    public ResponseEntity<?> start(@RequestBody StartRunRequest request) {
        if (request == null || request.feature() == null || request.feature().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "feature is required"));
        }

        RunService.StartOutcome outcome = runService.start(
                request.feature(), request.app(), request.flow(), request.intent(), request.product(),
                request.cif(), request.userid());

        if (outcome.accepted()) {
            return ResponseEntity.accepted().body(Map.of(
                    "runId", outcome.runId(),
                    "executor", outcome.executor(),
                    "stepCount", outcome.validation().stepCount()));
        }

        return switch (outcome.rejection()) {
            case INVALID_SCENARIO -> ResponseEntity.unprocessableEntity().body(outcome.validation());
            case NO_VOCABULARY -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No executor reachable, so the scenario could not be validated"));
            case NO_FREE_EXECUTOR -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "All executors are busy or unreachable",
                    "hint", "Retry shortly; each executor runs one scenario at a time"));
        };
    }

    /**
     * A PLAIN JSON ARRAY, still — this is a contract, not a style choice.
     *
     * app.js's renderHistory() does `runs.filter(...)` straight on the body.
     * Returning a Spring Page here would make it {content:[...],pageable:{...}},
     * `.filter` would be undefined, the history pane would render "Kayıt yok",
     * and NOTHING WOULD LOG ANYTHING — the UI's failure mode for a changed
     * contract is a blank panel.
     *
     * {@code limit} is new and optional. In memory the history was bounded by
     * the next restart; persistence removes that accidental bound, and nothing
     * otherwise stops a year of runs from becoming a huge response. app.js
     * sends no parameter and gets the configured default, which is more rows
     * than the pane shows without scrolling.
     *
     * The filter chips keep filtering client-side over those rows, so there is
     * no ?status= here and no index behind one. When someone needs "every
     * FAILED run in March" that is a separate change with a real requirement.
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Integer limit) {
        return runService.list(limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> status(@PathVariable UUID id) {
        return runService.status(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown run id: " + id)));
    }

    /** What this run left on disk: report, log, scenario, traces. */
    @GetMapping("/{id}/artifacts")
    public ResponseEntity<?> artifacts(@PathVariable UUID id) {
        return runService.artifacts(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Koşu bulunamadı ya da executor'a ulaşılamıyor: " + id)));
    }

    /**
     * One artifact, proxied from the executor that ran it — analysts never
     * reach an executor directly.
     *
     * The Cucumber HTML report embeds analyst-authored scenario text, so it is
     * served with a sandbox CSP: scripts still run (the report is interactive)
     * but in an opaque origin, where they cannot touch this application.
     */
    @GetMapping("/{id}/artifacts/**")
    public ResponseEntity<byte[]> artifact(@PathVariable UUID id, HttpServletRequest request) {
        String name = trailingPath(request, id);

        return runService.artifact(id, name)
                .map(upstream -> {
                    if (!upstream.getStatusCode().is2xxSuccessful()) {
                        return ResponseEntity.status(upstream.getStatusCode()).<byte[]>build();
                    }
                    HttpHeaders headers = new HttpHeaders();
                    upstream.getHeaders().forEach((key, values) -> {
                        if (COPIED_HEADERS.contains(key.toLowerCase())) {
                            headers.put(key, values);
                        }
                    });
                    headers.set("Content-Security-Policy", "sandbox allow-scripts");
                    headers.set("X-Content-Type-Options", "nosniff");
                    return ResponseEntity.ok().headers(headers).body(upstream.getBody());
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static final Set<String> COPIED_HEADERS =
            Set.of("content-type", "content-disposition", "content-length");

    private static String trailingPath(HttpServletRequest request, UUID runId) {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/api/v1/runs/" + runId + "/artifacts/";
        return (full != null && full.startsWith(prefix)) ? full.substring(prefix.length()) : "";
    }

    @PutMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable UUID id) {
        if (!runService.stop(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Unknown run id, or its executor is unreachable: " + id));
        }
        // Cooperative only: a hung Playwright call cannot be killed from Java,
        // so a container restart stays the backstop.
        return ResponseEntity.accepted().body(Map.of("runId", id, "requested", "stop"));
    }
}
