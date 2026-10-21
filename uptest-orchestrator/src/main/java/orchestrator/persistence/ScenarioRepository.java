package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScenarioRepository extends JpaRepository<ScenarioEntity, UUID> {

    List<ScenarioEntity> findByScreenIdInOrderByFileNameAsc(Collection<UUID> screenIds);

    List<ScenarioEntity> findByScreenIdOrderByFileNameAsc(UUID screenId);

    Optional<ScenarioEntity> findByScreenIdAndFileName(UUID screenId, String fileName);

    long countByScreenIdIn(Collection<UUID> screenIds);
}
