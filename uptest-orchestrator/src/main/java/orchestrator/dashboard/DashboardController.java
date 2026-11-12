package orchestrator.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The dashboard's reads; see docs/dashboard/README.md for what each number means.
 *
 * {@code period} is 24h, 7d or 30d (anything else reads as 7d); {@code cif}
 * narrows to one test login's runs, blank for all.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public DashboardService.Overview overview(@RequestParam(defaultValue = "7d") String period,
                                              @RequestParam(required = false) String cif) {
        return dashboard.overview(period, cif);
    }

    @GetMapping("/screens/{app}/{product}/{flow}")
    public ResponseEntity<?> screen(@PathVariable String app, @PathVariable String product,
                                    @PathVariable String flow,
                                    @RequestParam(defaultValue = "7d") String period,
                                    @RequestParam(required = false) String cif) {
        return dashboard.screen(app, product, flow, period, cif)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "Ekran bulunamadı: " + app + "/" + product + "/" + flow)));
    }

    /** One scenario's runs; the path ({@code <screen>/<file>}) travels as a parameter, it has a slash. */
    @GetMapping("/scenarios/{app}/{product}")
    public ResponseEntity<?> history(@PathVariable String app, @PathVariable String product,
                                     @RequestParam String path,
                                     @RequestParam(defaultValue = "30d") String period) {
        return dashboard.history(app, product, path, period)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "Senaryo bulunamadı: " + path)));
    }
}
