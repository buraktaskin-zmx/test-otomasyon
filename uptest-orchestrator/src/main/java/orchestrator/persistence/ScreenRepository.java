package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreenRepository extends JpaRepository<ScreenEntity, UUID> {

    List<ScreenEntity> findByMenuIdOrderBySortOrderAscFlowAsc(UUID menuId);

    Optional<ScreenEntity> findByMenuIdAndFlow(UUID menuId, String flow);
}
