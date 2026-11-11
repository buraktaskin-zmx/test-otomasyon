package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AiSessionRepository extends JpaRepository<AiSessionEntity, UUID> {

    List<AiSessionEntity> findByStartedAtGreaterThanEqualOrderByStartedAtDesc(Instant since);

    List<AiSessionEntity> findByParentIdOrderByStartedAtAsc(UUID parentId);

    /** The walk a tape belongs to — newest first, a tape id is a UUID so there is one. */
    List<AiSessionEntity> findByTapeIdOrderByStartedAtDesc(String tapeId);

    /**
     * Sessions the orchestrator was killed in the middle of. Their turns are
     * real and stay; only the ending is unknown, same as TEST_RUN's UNKNOWN.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update AiSessionEntity s
              set s.status = 'UNKNOWN',
                  s.finishedAt = :now,
                  s.errorMessage = :note
            where s.status = 'RUNNING'
              and s.startedAt < :cutoff
           """)
    int settleRunning(@Param("cutoff") Instant cutoff, @Param("now") Instant now, @Param("note") String note);
}
