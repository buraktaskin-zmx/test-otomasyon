package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.Length;

import java.time.Instant;
import java.util.UUID;

/**
 * One registered scenario: the natural-language fields and the Gherkin, in
 * separate columns (see orchestrator.product.ScenarioFields for why).
 *
 * Lists are stored one item per line in a single LONG32 column. A child
 * table per list would be four joins for a screen showing a few dozen rows,
 * and nothing ever queries inside a list.
 *
 * {@code file_name} keeps the name the scenario had as a file
 * ("01-ekran-acilisi-filtre-alanlari.feature"): it orders the table the way
 * the folder did, makes the UI's "path" (screen + "/" + file name) stable
 * across moves within one screen, and is the name an export writes.
 */
@Entity
@Table(name = "SCENARIO",
        uniqueConstraints = @UniqueConstraint(name = "UQ_SCENARIO_SCREEN_FILE",
                columnNames = {"screen_id", "file_name"}),
        indexes = @Index(name = "IX_SCENARIO_SCREEN", columnList = "screen_id"))
public class ScenarioEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Optimistic lock: two people saving the same scenario do not silently
     *  overwrite each other -- the second gets a conflict. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "screen_id", nullable = false)
    private UUID screenId;

    @Column(name = "file_name", nullable = false, length = 160)
    private String fileName;

    /**
     * The screen's "Başlık" this scenario is grouped under, or null for none.
     * An id that names no heading of the scenario's own screen also reads as
     * none -- see ScenarioStore.scenarios.
     */
    @Column(name = "heading_id")
    private UUID headingId;

    @Column(name = "title", nullable = false, length = Length.LONG32)
    private String title;

    @Column(name = "rules", length = Length.LONG32)
    private String rules;

    @Column(name = "needs", length = Length.LONG32)
    private String needs;

    @Column(name = "other_tags", length = Length.LONG32)
    private String otherTags;

    @Column(name = "preconditions", length = Length.LONG32)
    private String preconditions;

    @Column(name = "steps", length = Length.LONG32)
    private String steps;

    @Column(name = "expected", length = Length.LONG32)
    private String expected;

    @Column(name = "gherkin", nullable = false, length = Length.LONG32)
    private String gherkin;

    /** The file it was imported from, for whoever wonders where a row came from. */
    @Column(name = "source_path", length = 512)
    private String sourcePath;

    /**
     * The rules and aliases this scenario names, AS THEY READ when somebody
     * last reviewed it -- see orchestrator.product.SourceSnapshot. JSON, and
     * the whole text rather than a hash, because the question an analyst can
     * act on is "what changed", not "did it change".
     *
     * Null means nobody has stamped this scenario yet, and that reads as "no
     * finding": a badge that is on for every row says nothing about any of
     * them.
     */
    @Column(name = "reviewed_sources", length = Length.LONG32)
    private String reviewedSources;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScenarioEntity() {
        // JPA
    }

    public ScenarioEntity(UUID screenId, String fileName, String sourcePath) {
        this.id = UUID.randomUUID();
        this.screenId = screenId;
        this.fileName = fileName;
        this.sourcePath = sourcePath;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getScreenId() {
        return screenId;
    }

    public void setScreenId(UUID screenId) {
        this.screenId = screenId;
    }

    public String getFileName() {
        return fileName;
    }

    public UUID getHeadingId() {
        return headingId;
    }

    public void setHeadingId(UUID headingId) {
        this.headingId = headingId;
    }

    public String getTitle() {
        return title;
    }

    public String getRules() {
        return rules;
    }

    public String getNeeds() {
        return needs;
    }

    public String getOtherTags() {
        return otherTags;
    }

    public String getPreconditions() {
        return preconditions;
    }

    public String getSteps() {
        return steps;
    }

    public String getExpected() {
        return expected;
    }

    public String getGherkin() {
        return gherkin;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getReviewedSources() {
        return reviewedSources;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    /** Stamps what the scenario's rules and aliases say right now. */
    public void setReviewedSources(String reviewedSources) {
        this.reviewedSources = reviewedSources;
        this.reviewedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Every field at once -- the store always writes the whole scenario. */
    public void setContent(String title, String rules, String needs, String otherTags,
                           String preconditions, String steps, String expected, String gherkin) {
        this.title = title;
        this.rules = rules;
        this.needs = needs;
        this.otherTags = otherTags;
        this.preconditions = preconditions;
        this.steps = steps;
        this.expected = expected;
        this.gherkin = gherkin;
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
