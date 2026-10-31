package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ScenarioHeadingRepository extends JpaRepository<ScenarioHeadingEntity, UUID> {

    List<ScenarioHeadingEntity> findByScreenIdOrderByCreatedAtAscIdAsc(UUID screenId);

    List<ScenarioHeadingEntity> findByScreenIdInOrderByCreatedAtAscIdAsc(Collection<UUID> screenIds);
}
