package orchestrator.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the mapping decisions that fail SILENTLY, and one that cannot be undone.
 *
 * @DataJpaTest replaces the datasource with an in-memory H2 and rolls back each
 * test, so none of this can reach data/uptest.mv.db. It also loads only the JPA
 * slice — a @SpringBootTest here would try to spawn the MCP stdio server
 * (spring.ai.mcp.client.stdio.servers-configuration in application.properties)
 * during context refresh. Do not write one.
 *
 * It needs spring-boot-starter-data-jpa-test on the test classpath: Boot 4 split
 * the test slices out of spring-boot-test-autoconfigure, so
 * spring-boot-starter-test ALONE does not provide @DataJpaTest.
 */
@DataJpaTest
class TestRunRepositoryTest {

    @Autowired
    private TestRunRepository runs;

    @Autowired
    private TestRunStepRepository steps;

    @Autowired
    private TestEntityManager em;

    // ------------------------------------------------- the irreversible one

    /**
     * THE TEST THIS CLASS EXISTS FOR.
     *
     * @JdbcTypeCode(LONGVARCHAR) + length = Length.LONG32 is supposed to give
     * an unbounded text column. If it comes out as VARCHAR(255) instead, the
     * first real scenario dies with "Value too long for column" — and because
     * ddl-auto=update NEVER WIDENS A COLUMN, it keeps dying after the
     * annotation is fixed. The only cure on a live system is deleting the
     * database file.
     *
     * So this is cheap on day one and impossible on day ninety.
     */
    @Test
    @DisplayName("feature, intent and last_status_json are really unbounded")
    void longTextColumnsAreUnbounded() {
        String huge = "x".repeat(200_000);
        UUID id = UUID.randomUUID();

        TestRunEntity row = new TestRunEntity(id, Instant.now(), "RUNNING", "executor-1:9035",
                "Uzun senaryo", "ngc", "banko-payment", huge, huge, null);
        row.setLastStatusJson(huge);
        runs.saveAndFlush(row);
        em.clear();

        TestRunEntity reloaded = runs.findById(id).orElseThrow();
        assertThat(reloaded.getFeature()).hasSize(200_000);
        assertThat(reloaded.getIntent()).hasSize(200_000);
        assertThat(reloaded.getLastStatusJson()).hasSize(200_000);
    }

    // --------------------------------------------------- the silent one

    /**
     * The pre-assigned-UUID save() trap.
     *
     * Without @Version Long, JpaMetamodelEntityInformation.isNew() returns
     * false for an entity whose assigned @Id is already set, so save() calls
     * em.merge() — which issues a pointless SELECT and RETURNS A DIFFERENT
     * MANAGED INSTANCE, leaving the object the caller holds detached forever.
     * Every field set on it after saving is then silently never flushed.
     *
     * isSameAs is the precise assertion: persist() leaves you holding the
     * managed instance, merge() hands you a copy.
     */
    @Test
    @DisplayName("save() on an assigned UUID id is a persist, not a merge")
    void saveIsPersistNotMerge() {
        TestRunEntity row = newRun(UUID.randomUUID(), Instant.now());

        TestRunEntity saved = runs.saveAndFlush(row);

        assertThat(saved).isSameAs(row);
        assertThat(saved.getVersion()).isZero();
    }

    /**
     * The consequence of the above, stated as the behaviour that actually
     * breaks: RunStore.apply mutates the entity it fetched and never calls
     * save(), relying on the dirty check at commit.
     */
    @Test
    @DisplayName("a field set after save() is flushed, because the instance is managed")
    void postSaveMutationIsFlushed() {
        UUID id = UUID.randomUUID();
        TestRunEntity saved = runs.saveAndFlush(newRun(id, Instant.now()));

        saved.setStatus("DONE");
        saved.setFinishedAt(Instant.now());
        em.flush();
        em.clear();

        TestRunEntity reloaded = runs.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("DONE");
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    // --------------------------------------------------------- the listing

    @Test
    @DisplayName("findRecent is newest first and honours the limit")
    void findRecentIsNewestFirstAndBounded() {
        Instant now = Instant.now();
        UUID oldest = persist(now.minusSeconds(300));
        UUID middle = persist(now.minusSeconds(200));
        UUID newest = persist(now.minusSeconds(100));
        em.flush();
        em.clear();

        List<UUID> page = ids(runs.findRecent(PageRequest.of(0, 2)));

        assertThat(page).containsExactly(newest, middle).doesNotContain(oldest);
    }

    /**
     * H2 stores timestamp(6), so two runs submitted in the same microsecond
     * tie on submitted_at. Without the `, id desc` tiebreaker the order
     * between them is whatever the engine feels like, and the history pane
     * flickers between refreshes.
     *
     * STABILITY is asserted rather than a specific order: H2 compares UUIDs by
     * their binary value and Java's UUID.compareTo compares signed longs, so
     * an expected-order assertion would be testing an ordering neither side
     * promises. Stability is the property the UI actually needs.
     */
    @Test
    @DisplayName("ties on submitted_at still produce a stable order")
    void orderIsStableOnTies() {
        Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS);
        persist(sameInstant);
        persist(sameInstant);
        persist(sameInstant);
        em.flush();
        em.clear();

        List<UUID> first = ids(runs.findRecent(PageRequest.of(0, 10)));
        em.clear();
        List<UUID> second = ids(runs.findRecent(PageRequest.of(0, 10)));

        assertThat(first).hasSize(3).isEqualTo(second);
    }

    @Test
    @DisplayName("findExecutorNameById returns just the one string")
    void executorNameProjection() {
        UUID id = UUID.randomUUID();
        runs.saveAndFlush(newRun(id, Instant.now()));
        em.clear();

        assertThat(runs.findExecutorNameById(id)).contains("executor-1:9035");
        assertThat(runs.findExecutorNameById(UUID.randomUUID())).isEmpty();
    }

    // ---------------------------------------------------- reconciliation

    @Test
    @DisplayName("settleStale touches only the rows that are unsettled AND old")
    void settleStaleHitsOnlyOrphans() {
        Instant now = Instant.now();

        UUID settled = persist(now.minusSeconds(7200));
        runs.findById(settled).orElseThrow().setFinishedAt(now.minusSeconds(7100));

        UUID orphan = persist(now.minusSeconds(7200));
        UUID young = persist(now.minusSeconds(10));
        em.flush();

        int count = runs.settleStale(now.minusSeconds(1800), now, "orphaned");

        assertThat(count).isOne();
        assertThat(runs.findById(orphan).orElseThrow().getStatus()).isEqualTo("UNKNOWN");
        assertThat(runs.findById(orphan).orElseThrow().getErrorMessage()).isEqualTo("orphaned");
        assertThat(runs.findById(orphan).orElseThrow().getFinishedAt()).isNotNull();

        // Untouched: one already settled, one not yet old enough.
        assertThat(runs.findById(settled).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(runs.findById(young).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(runs.findById(young).orElseThrow().getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("findByFinishedAtIsNull finds exactly the unsettled rows")
    void unsettledQuery() {
        UUID open = persist(Instant.now().minusSeconds(60));
        UUID done = persist(Instant.now().minusSeconds(30));
        runs.findById(done).orElseThrow().setFinishedAt(Instant.now());
        em.flush();
        em.clear();

        assertThat(ids(runs.findByFinishedAtIsNullOrderBySubmittedAtDesc()))
                .containsExactly(open);
    }

    // ------------------------------------------------------- timestamps

    /**
     * NEVER isEqualTo here. H2 keeps six fractional digits; Instant.now()
     * carries nanoseconds. A failure that looks like a bug in the mapping is
     * almost always this truncation.
     */
    @Test
    @DisplayName("Instant survives the round trip to within a microsecond")
    void instantRoundTrip() {
        Instant submitted = Instant.now();
        UUID id = UUID.randomUUID();
        runs.saveAndFlush(newRun(id, submitted));
        em.clear();

        assertThat(runs.findById(id).orElseThrow().getSubmittedAt())
                .isCloseTo(submitted, within(1, ChronoUnit.MICROS));
    }

    // ------------------------------------------------------------- steps

    /**
     * The step timeline is a SNAPSHOT, rewritten whenever it changes during a
     * run. It must replace, never accumulate — and the delete must reach the
     * database before the inserts, or the unique constraint on
     * (run_id, step_index) fires. That is what flushAutomatically on
     * deleteByRunId is for.
     */
    @Test
    @DisplayName("the step snapshot replaces rather than accumulates")
    void stepSnapshotReplaces() {
        UUID runId = UUID.randomUUID();
        runs.saveAndFlush(newRun(runId, Instant.now()));

        steps.saveAll(List.of(
                new TestRunStepEntity(runId, 0, "Given", "login with cif \"***\"", "PASSED", 120L, null),
                new TestRunStepEntity(runId, 1, "And", "click \"Devam\"", "RUNNING", null, null),
                new TestRunStepEntity(runId, 2, "Then", "verify \"x\" text is displayed", "PENDING", null, null)));
        em.flush();

        steps.deleteByRunId(runId);
        steps.saveAll(List.of(
                new TestRunStepEntity(runId, 0, "Given", "login with cif \"***\"", "PASSED", 120L, null),
                new TestRunStepEntity(runId, 1, "And", "click \"Devam\"", "FAILED", 940L, "boom")));
        em.flush();
        em.clear();

        List<TestRunStepEntity> timeline = steps.findByRunIdOrderByStepIndexAsc(runId);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).getStepIndex()).isZero();
        assertThat(timeline.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(timeline.get(1).getDurationMs()).isEqualTo(940L);
        assertThat(timeline.get(1).getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("durationMs stays null for a step that has not finished")
    void unfinishedStepHasNoDuration() {
        UUID runId = UUID.randomUUID();
        runs.saveAndFlush(newRun(runId, Instant.now()));
        steps.saveAndFlush(new TestRunStepEntity(runId, 0, "Given", "login", "RUNNING", null, null));
        em.clear();

        assertThat(steps.findByRunIdOrderByStepIndexAsc(runId).get(0).getDurationMs()).isNull();
    }

    @Test
    @DisplayName("step_text is unbounded too")
    void stepTextIsUnbounded() {
        UUID runId = UUID.randomUUID();
        runs.saveAndFlush(newRun(runId, Instant.now()));
        steps.saveAndFlush(new TestRunStepEntity(
                runId, 0, "Given", "y".repeat(100_000), "PASSED", 1L, null));
        em.clear();

        assertThat(steps.findByRunIdOrderByStepIndexAsc(runId).get(0).getStepText())
                .hasSize(100_000);
    }

    // ----------------------------------------------------------- helpers

    private UUID persist(Instant submittedAt) {
        UUID id = UUID.randomUUID();
        runs.save(newRun(id, submittedAt));
        return id;
    }

    private static TestRunEntity newRun(UUID id, Instant submittedAt) {
        return new TestRunEntity(id, submittedAt, "RUNNING", "executor-1:9035",
                "Kullanıcı ödeme talep eder", "ngc", "banko-payment",
                "banko ödeme testi", "Feature: X\n  Scenario: Y\n", null);
    }

    private static List<UUID> ids(List<TestRunEntity> rows) {
        return rows.stream().map(TestRunEntity::getId).toList();
    }
}
