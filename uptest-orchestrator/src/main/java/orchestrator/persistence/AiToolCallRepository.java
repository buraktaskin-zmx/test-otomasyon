package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiToolCallRepository extends JpaRepository<AiToolCallEntity, UUID> {

    List<AiToolCallEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);
}
