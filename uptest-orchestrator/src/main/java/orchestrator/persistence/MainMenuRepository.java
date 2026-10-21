package orchestrator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MainMenuRepository extends JpaRepository<MainMenuEntity, UUID> {

    List<MainMenuEntity> findAllByOrderBySortOrderAscAppAscProductKeyAsc();

    Optional<MainMenuEntity> findByAppAndProductKey(String app, String productKey);
}
