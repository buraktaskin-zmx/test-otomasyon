package orchestrator.dashboard;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * At startup, ties runs recorded before TEST_RUN had a scenario id to the
 * scenario their title names today (DashboardService.backfill). Runs after
 * the import, so a fresh database's scenarios are there to match.
 *
 * An event listener, not an ApplicationRunner, for ScenarioImporter's
 * reason: ApplicationRunner beans also run under {@code @SpringBootTest}, and
 * this one writes. A failure is logged and never stops the application —
 * an unlinked run only stays "≈" on the dashboard.
 */
@Component
public class RunScenarioBackfill {

    private final DashboardService dashboard;

    public RunScenarioBackfill(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @Order(100)
    @EventListener(ApplicationReadyEvent.class)
    public void link() {
        try {
            int linked = dashboard.backfill();
            if (linked > 0) {
                System.err.println("[dashboard] " + linked + " eski koşu senaryosuna bağlandı (başlık eşleşmesiyle)");
            }
        } catch (RuntimeException e) {
            System.err.println("[dashboard] eski koşular senaryoya bağlanamadı: " + e);
        }
    }
}
