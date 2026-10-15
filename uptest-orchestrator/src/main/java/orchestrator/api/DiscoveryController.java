package orchestrator.api;

import orchestrator.catalog.CatalogDiscoveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the browser through one screen to read its fields, instead of an
 * analyst hand-writing a catalogs/&lt;app&gt;/&lt;flow&gt;.yaml. Analysts never
 * reach an executor directly; this proxies exactly like {@link RunController}
 * does for a normal run.
 */
@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {

    public record StageBody(Map<String, String> values, String button) {
    }

    /**
     * @param entryRow optional: a result-grid row to pick before entryButton,
     *                 for sub-menus that land on a list rather than a form
     */
    public record DiscoverRequestBody(
            String app, String cif, String userid, String mainMenu, String subMenu,
            String entryRow, String entryButton,
            List<StageBody> stages) {
    }

    private final CatalogDiscoveryService discovery;

    public DiscoveryController(CatalogDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody DiscoverRequestBody body) {
        if (body == null || isBlank(body.app()) || isBlank(body.cif())
                || isBlank(body.mainMenu()) || isBlank(body.subMenu())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "app, cif, mainMenu and subMenu are required"));
        }
        if ("NGC".equalsIgnoreCase(body.app()) && isBlank(body.userid())) {
            return ResponseEntity.badRequest().body(Map.of("error", "userid is required for NGC"));
        }

        List<CatalogDiscoveryService.Stage> stages = new ArrayList<>();
        if (body.stages() != null) {
            for (StageBody stage : body.stages()) {
                // Without a button the flow cannot leave the screen, and the scan
                // that follows it is what makes the next screen discoverable.
                if (stage == null || isBlank(stage.button())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "every stage needs a button"));
                }
                stages.add(new CatalogDiscoveryService.Stage(stage.values(), stage.button()));
            }
        }

        try {
            CatalogDiscoveryService.StartOutcome outcome = discovery.start(new CatalogDiscoveryService.DiscoverRequest(
                    body.app(), body.cif(), body.userid(), body.mainMenu(), body.subMenu(),
                    body.entryRow(), body.entryButton(),
                    stages));

            if (!outcome.accepted()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "All executors are busy or unreachable"));
            }
            return ResponseEntity.accepted().body(Map.of(
                    "runId", outcome.runId(), "executor", outcome.executor()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> status(@PathVariable UUID id) {
        return discovery.status(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown discovery run id: " + id)));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
