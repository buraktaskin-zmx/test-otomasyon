package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.Length;

import java.util.UUID;

/**
 * One "ana menü" of the Kayıtlı Senaryolar tree, e.g. ÖDEMELER.
 *
 * Carries what product.yaml used to carry about the product's IDENTITY: its
 * app, the key its rule files live under ({@code products/<app>/<key>/}), its
 * name and the menu it sits in. The rules themselves stay in files -- see
 * ProductService.load, which joins the two.
 *
 * Column names are explicit for the reasons TestRunEntity spells out (H2 2.x
 * reserved words, ddl-auto=update never renames), and every long text is
 * Length.LONG32 because ddl-auto=update never widens a varchar(255) later.
 */
@Entity
@Table(name = "MAIN_MENU", uniqueConstraints =
        @UniqueConstraint(name = "UQ_MAIN_MENU_APP_KEY", columnNames = {"app", "product_key"}))
public class MainMenuEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Boxed and null until persisted -- see TestRunEntity.version for why. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** "ngc" or "ngi", lower case as the products/ folders are named. */
    @Column(name = "app", nullable = false, length = 16)
    private String app;

    /** The folder name under products/&lt;app&gt;/, e.g. maas-odemeleri. */
    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    /** The menu as the tree shows it ("Ödemeler") -- product.yaml's category. */
    @Column(name = "category", nullable = false, length = 128)
    private String category;

    /** The product's own name ("Maaş Ödemeleri"). */
    @Column(name = "product_name", nullable = false, length = 128)
    private String productName;

    @Column(name = "description", length = Length.LONG32)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected MainMenuEntity() {
        // JPA
    }

    public MainMenuEntity(String app, String productKey, String category, String productName,
                          String description, int sortOrder) {
        this.id = UUID.randomUUID();
        this.app = app;
        this.productKey = productKey;
        this.category = category;
        this.productName = productName;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getApp() {
        return app;
    }

    public String getProductKey() {
        return productKey;
    }

    public String getCategory() {
        return category;
    }

    public String getProductName() {
        return productName;
    }

    public String getDescription() {
        return description;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
