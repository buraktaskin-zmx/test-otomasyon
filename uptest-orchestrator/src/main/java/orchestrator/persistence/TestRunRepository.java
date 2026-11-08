package orchestrator.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestRunRepository extends JpaRepository<TestRunEntity, UUID> {

    /**
     * The history listing: ONE statement, newest first, bounded.
     *
     * `, r.id desc` is not decoration. H2 stores timestamp(6), so two runs
     * submitted in the same microsecond tie on submitted_at and the order
     * between them is whatever the engine feels like — which makes the listing
     * flicker between refreshes and any ordering test flaky.
     *
     * No fetch join, no @EntityGraph, no projection: there is no association
     * on TestRunEntity to N+1 over. The step timeline the UI renders comes out
     * of this row's last_status_json. Verify with
     * logging.level.org.hibernate.SQL=DEBUG — GET /api/v1/runs must issue
     * exactly one SELECT plus zero-to-k UPDATEs, never 1+N.
     *
     * Returns List, not Page, and takes a Pageable: a repository method
     * returning Page triggers a second count(*) query nobody needs here.
     */
    @Query("select r from TestRunEntity r order by r.submittedAt desc, r.id desc")
    List<TestRunEntity> findRecent(Pageable pageable);

    /**
     * artifacts(), artifact() and stop() need ONE string: which executor to
     * proxy to. A findById would drag the feature text and the whole status
     * JSON across for it.
     */
    @Query("select r.executorName from TestRunEntity r where r.id = :id")
    Optional<String> findExecutorNameById(@Param("id") UUID id);

    /**
     * Startup reconciliation — see RunReconciler.
     *
     * Two things about this being a JPQL bulk update, because it BYPASSES both
     * optimistic locking and the persistence context:
     *
     *   r.version = r.version + 1 is written BY HAND. Without it, a managed
     *   copy of one of these rows living elsewhere keeps its stale version and
     *   its next flush fails with a lock error nobody can trace back to here.
     *
     *   clearAutomatically = true, or an entity already in the context keeps
     *   the pre-update status for the rest of the transaction — a silent stale
     *   read.
     *
     * @return how many rows were settled; nonzero means someone lost
     *         visibility into a run, which is why RunReconciler logs it at WARN
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update TestRunEntity r
              set r.status = 'UNKNOWN',
                  r.finishedAt = :now,
                  r.errorMessage = :note,
                  r.version = r.version + 1
            where r.finishedAt is null
              and r.submittedAt < :cutoff
           """)
    int settleStale(@Param("cutoff") Instant cutoff,
                    @Param("now") Instant now,
                    @Param("note") String note);

    /** Diagnostics: what is still unsettled, newest first. */
    List<TestRunEntity> findByFinishedAtIsNullOrderBySubmittedAtDesc();

    /** The dashboard's view of a run: the short columns only. */
    interface RunSummary {
        UUID getId();

        Instant getSubmittedAt();

        String getStatus();

        String getExecutorName();

        String getScenarioName();

        String getApp();

        String getFlow();

        String getProduct();

        UUID getScenarioId();

        String getCif();

        String getTestUserId();

        String getRunTrigger();

        UUID getBatchId();
    }

    /** Runs not yet tied to a saved scenario — what the startup backfill matches by title. */
    @Query("""
           select r.id as id, r.submittedAt as submittedAt, r.status as status,
                  r.executorName as executorName, r.scenarioName as scenarioName,
                  r.app as app, r.flow as flow, r.product as product,
                  r.scenarioId as scenarioId, r.cif as cif, r.testUserId as testUserId,
                  r.runTrigger as runTrigger, r.batchId as batchId
             from TestRunEntity r
            where r.scenarioId is null
           """)
    List<RunSummary> findUnlinkedSummaries();

    /**
     * Ties one old run to a scenario, only if nothing tied it already. A bulk
     * update, so the version is bumped by hand — see settleStale.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update TestRunEntity r
              set r.scenarioId = :scenarioId,
                  r.version = r.version + 1
            where r.id = :id and r.scenarioId is null
           """)
    int linkScenario(@Param("id") UUID id, @Param("scenarioId") UUID scenarioId);

    /**
     * Every run submitted since a moment, newest first, WITHOUT the feature
     * text and the status JSON — a month of runs with those would be tens of
     * megabytes read to count rows.
     */
    @Query("""
           select r.id as id, r.submittedAt as submittedAt, r.status as status,
                  r.executorName as executorName, r.scenarioName as scenarioName,
                  r.app as app, r.flow as flow, r.product as product,
                  r.scenarioId as scenarioId, r.cif as cif, r.testUserId as testUserId,
                  r.runTrigger as runTrigger, r.batchId as batchId
             from TestRunEntity r
            where r.submittedAt >= :since
            order by r.submittedAt desc, r.id desc
           """)
    List<RunSummary> findSummariesSince(@Param("since") Instant since);
}
