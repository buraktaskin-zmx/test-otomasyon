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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    public record StartRunRequest(String feature) {
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

        RunService.StartOutcome outcome = runService.start(request.feature());

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

    @GetMapping
    public List<Map<String, Object>> list() {
        return runService.list();
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
