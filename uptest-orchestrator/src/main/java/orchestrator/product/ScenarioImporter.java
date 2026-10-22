package orchestrator.product;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup, copies into the database any product folder it does not know
 * yet (see ProductService.importMissing). The first start after the upgrade
 * imports everything; later starts import only a product that arrived in a
 * git pull, and never touch one already in the database.
 *
 * An event listener rather than an ApplicationRunner for the reason
 * RunReconciler gives: ApplicationRunner beans also run under
 * {@code @SpringBootTest}, and this one writes.
 */
@Component
public class ScenarioImporter {

    private final ProductService products;

    public ScenarioImporter(ProductService products) {
        this.products = products;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importMissing() {
        ProductService.Imported imported = products.importMissing();
        if (imported.menus() > 0) {
            // On stderr like the module's other diagnostics: greppable in the
            // console with no debug flag, and the one line that says the
            // database now holds what the files held.
            System.err.println("[senaryo] veritabanına aktarıldı: " + imported.menus() + " ana menü, "
                    + imported.screens() + " ekran, " + imported.scenarios() + " senaryo");
        }
        imported.skipped().forEach(reason -> System.err.println("[senaryo] aktarılmadı: " + reason));

        // Scenarios that predate stamping -- everything already in a database
        // built before this feature. Without this the "kural değişti" badge
        // would only ever work for scenarios written afterwards.
        int stamped = products.stampUnreviewed();
        if (stamped > 0) {
            System.err.println("[senaryo] " + stamped
                    + " senaryo, kurallarının bugünkü hâline göre damgalandı");
        }
    }
}
