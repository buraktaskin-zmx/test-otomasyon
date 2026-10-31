package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A "Başlık": a named group of scenarios on ONE screen, e.g. "Fatura Oluştur"
 * under Alıcı Listesi İşlemleri.
 *
 * A heading belongs to a screen, not to a menu, so a scenario moved to another
 * screen leaves its heading behind (ScenarioStore.move clears it). Scenarios
 * point at their heading through SCENARIO.heading_id; a heading points at
 * nothing, so renaming or deleting one later touches one row.
 *
 * No unique constraint on the name: "the same name" means the same name with
 * case and the Turkish I/İ/ı ignored, which a database collation does not
 * express. ScenarioStore.addHeading enforces it instead.
 */
@Entity
@Table(name = "SCENARIO_HEADING",
        indexes = @Index(name = "IX_HEADING_SCREEN", columnList = "screen_id"))
public class ScenarioHeadingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "screen_id", nullable = false)
    private UUID screenId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** Headings are listed in the order they were made. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScenarioHeadingEntity() {
        // JPA
    }

    public ScenarioHeadingEntity(UUID screenId, String name) {
        this.id = UUID.randomUUID();
        this.screenId = screenId;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getScreenId() {
        return screenId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
