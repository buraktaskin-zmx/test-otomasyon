package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TestRunStepRepository extends JpaRepository<TestRunStepEntity, Long> {

    /**
     * ONE statement.
     *
     * The derived deleteAllByRunId would SELECT the rows and then DELETE them
     * one at a time, which for a per-poll snapshot rewrite is 1 + N statements
     * every two seconds.
     *
     * flushAutomatically because the very next thing in the same transaction
     * is a saveAll of the replacement rows, and the unique constraint on
     * (run_id, step_index) fires if the delete has not reached the database
     * yet. clearAutomatically so nothing in the context still holds the rows
     * this just removed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TestRunStepEntity s where s.runId = :runId")
    void deleteByRunId(@Param("runId") UUID runId);

    List<TestRunStepEntity> findByRunIdOrderByStepIndexAsc(UUID runId);
}
