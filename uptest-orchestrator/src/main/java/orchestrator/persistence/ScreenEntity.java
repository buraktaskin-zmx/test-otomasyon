package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * One screen under an ana menü, e.g. "Alıcı Listesi İşlemleri".
 *
 * The menu is held as a plain id, not a @ManyToOne: open-in-view is off, so
 * a lazy association read outside ScenarioStore's transaction would throw,
 * and nothing here needs the object graph.
 */
@Entity
@Table(name = "SCREEN",
        uniqueConstraints = @UniqueConstraint(name = "UQ_SCREEN_MENU_FLOW", columnNames = {"menu_id", "flow"}),
        indexes = @Index(name = "IX_SCREEN_MENU", columnList = "menu_id"))
public class ScreenEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "menu_id", nullable = false)
    private UUID menuId;

    /** The slug, e.g. alici-listesi-islemleri -- also the catalog's flow name. */
    @Column(name = "flow", nullable = false, length = 64)
    private String flow;

    /** The name the tree shows. */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ScreenEntity() {
        // JPA
    }

    public ScreenEntity(UUID menuId, String flow, String name, int sortOrder) {
        this.id = UUID.randomUUID();
        this.menuId = menuId;
        this.flow = flow;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMenuId() {
        return menuId;
    }

    public String getFlow() {
        return flow;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
