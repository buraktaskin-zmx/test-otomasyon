package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiTurnRepository extends JpaRepository<AiTurnEntity, UUID> {

    List<AiTurnEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);
}
