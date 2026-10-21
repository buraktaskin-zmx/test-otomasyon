package orchestrator.product;

import orchestrator.persistence.MainMenuEntity;
import orchestrator.persistence.MainMenuRepository;
import orchestrator.persistence.ScenarioEntity;
import orchestrator.persistence.ScenarioRepository;
import orchestrator.persistence.ScreenEntity;
import orchestrator.persistence.ScreenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only bean that touches the MAIN_MENU, SCREEN and SCENARIO tables --
 * the same arrangement RunStore has for the run history, and for the same
 * reason: @Transactional works through a proxy, so the transactional methods
 * have to live on a bean OTHER than the one calling them.
 *
 * Everything it hands out is an immutable record, never an entity. Open-in-view
 * is off, so an entity escaping a transaction is a detached object one lazy
 * read away from an exception, and one accidental setter away from a write
 * nobody asked for.
 *
 * Identity towards the UI is still the "path" the file-based registry used
 * ({@code <screen>/<file name>}), so the tree, the table and every URL keep
 * working unchanged; the UUID stays inside.
 */
@Service
public class ScenarioStore {

    public record Screen(UUID id, String flow, String name) {
    }

    public record Menu(UUID id, String app, String key, String category, String name,
                       String description, List<Screen> screens) {
    }

    public record Stored(UUID id, long version, String flow, String fileName,
                         ScenarioFields fields, String sourcePath) {

        public String path() {
            return flow + "/" + fileName;
        }
    }

    public enum Outcome { SAVED, NOT_FOUND, CONFLICT }

    public record Saved(Outcome outcome, Stored scenario) {
    }

    private final MainMenuRepository menus;
    private final ScreenRepository screens;
    private final ScenarioRepository scenarios;

    public ScenarioStore(MainMenuRepository menus, ScreenRepository screens,
                         ScenarioRepository scenarios) {
        this.menus = menus;
        this.screens = screens;
        this.scenarios = scenarios;
    }

    /* ------------------------------ reading ------------------------------ */

    @Transactional(readOnly = true)
    public List<Menu> menus() {
        return menus.findAllByOrderBySortOrderAscAppAscProductKeyAsc().stream()
                .map(this::menuOf)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Menu> menu(String app, String key) {
        return menus.findByAppAndProductKey(app, key).map(this::menuOf);
    }

    @Transactional(readOnly = true)
    public List<Stored> scenarios(String app, String key) {
        Optional<MainMenuEntity> menu = menus.findByAppAndProductKey(app, key);
        if (menu.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> flows = screens.findByMenuIdOrderBySortOrderAscFlowAsc(menu.get().getId())
                .stream().collect(Collectors.toMap(ScreenEntity::getId, ScreenEntity::getFlow));
        if (flows.isEmpty()) {
            return List.of();
        }
        return scenarios.findByScreenIdInOrderByFileNameAsc(flows.keySet()).stream()
                .map(entity -> stored(entity, flows.get(entity.getScreenId())))
                .sorted((a, b) -> a.path().compareTo(b.path()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(String app, String key) {
        return menus.findByAppAndProductKey(app, key)
                .map(menu -> {
                    List<UUID> ids = screens.findByMenuIdOrderBySortOrderAscFlowAsc(menu.getId())
                            .stream().map(ScreenEntity::getId).toList();
                    return ids.isEmpty() ? 0L : scenarios.countByScreenIdIn(ids);
                })
                .orElse(0L);
    }

    @Transactional(readOnly = true)
    public Optional<Stored> scenario(String app, String key, String path) {
        return locate(app, key, path).map(found -> stored(found.entity, found.flow));
    }

    /** The file names already taken on one screen, to number a new one. */
    @Transactional(readOnly = true)
    public List<String> fileNames(String app, String key, String flow) {
        return screen(app, key, flow)
                .map(screen -> scenarios.findByScreenIdOrderByFileNameAsc(screen.getId()).stream()
                        .map(ScenarioEntity::getFileName).toList())
                .orElse(List.of());
    }

    /* ------------------------------ writing ------------------------------ */

    @Transactional
    public Menu createMenu(String app, String key, String category, String name, String description) {
        int order = (int) menus.count();
        MainMenuEntity menu = menus.save(new MainMenuEntity(app, key, category, name, description, order));
        return menuOf(menu);
    }

    /** @return the new screen, or empty when the menu does not exist */
    @Transactional
    public Optional<Screen> addScreen(String app, String key, String flow, String name) {
        return menus.findByAppAndProductKey(app, key).map(menu -> {
            int order = screens.findByMenuIdOrderBySortOrderAscFlowAsc(menu.getId()).size();
            ScreenEntity screen = screens.save(new ScreenEntity(menu.getId(), flow, name, order));
            return new Screen(screen.getId(), screen.getFlow(), screen.getName());
        });
    }

    /** @return the new scenario, or empty when the screen does not exist */
    @Transactional
    public Optional<Stored> createScenario(String app, String key, String flow, String fileName,
                                           ScenarioFields fields, String sourcePath) {
        return screen(app, key, flow).map(screen -> {
            ScenarioEntity entity = new ScenarioEntity(screen.getId(), fileName, sourcePath);
            write(entity, fields);
            ScenarioEntity saved = scenarios.save(entity);
            return stored(saved, flow);
        });
    }

    /**
     * Rewrites one scenario.
     *
     * {@code change} receives the fields as they are NOW, inside the
     * transaction, and returns the fields to write -- or null to refuse, in
     * which case nothing is written and the outcome is SAVED with a null
     * scenario (the caller already knows why it refused).
     *
     * @param expectedVersion the version the editor started from, or null for
     *                        "whatever is there"; a mismatch is a CONFLICT
     */
    @Transactional
    public Saved save(String app, String key, String path, Long expectedVersion,
                      Function<ScenarioFields, ScenarioFields> change) {
        Optional<Located> found = locate(app, key, path);
        if (found.isEmpty()) {
            return new Saved(Outcome.NOT_FOUND, null);
        }
        ScenarioEntity entity = found.get().entity;
        if (expectedVersion != null && !expectedVersion.equals(entity.getVersion())) {
            return new Saved(Outcome.CONFLICT, stored(entity, found.get().flow));
        }
        ScenarioFields next = change.apply(fieldsOf(entity));
        if (next == null) {
            return new Saved(Outcome.SAVED, null);
        }
        write(entity, next);
        // Flushed here, not at commit, so the version handed back is the new one.
        ScenarioEntity saved = scenarios.saveAndFlush(entity);
        return new Saved(Outcome.SAVED, stored(saved, found.get().flow));
    }

    /**
     * Moves a scenario to another screen of the same app.
     *
     * @return the new path, or null with {@code refused} explaining why
     */
    @Transactional
    public ProductService.Created move(String app, String key, String path, String toKey, String toFlow) {
        Optional<Located> found = locate(app, key, path);
        if (found.isEmpty()) {
            return new ProductService.Created(null, "Senaryo bulunamadı: " + path);
        }
        Optional<ScreenEntity> target = screen(app, toKey, toFlow);
        if (target.isEmpty()) {
            return new ProductService.Created(null, "Ekran tanımlı değil: " + toFlow);
        }
        ScenarioEntity entity = found.get().entity;
        if (target.get().getId().equals(entity.getScreenId())) {
            return new ProductService.Created(null, "Senaryo zaten bu ekranda");
        }
        if (scenarios.findByScreenIdAndFileName(target.get().getId(), entity.getFileName()).isPresent()) {
            return new ProductService.Created(null,
                    "Hedef ekranda aynı adlı bir senaryo var: " + entity.getFileName());
        }
        entity.setScreenId(target.get().getId());
        entity.touch();
        return new ProductService.Created(toFlow + "/" + entity.getFileName(), null);
    }

    /* ------------------------------ helpers ------------------------------ */

    private record Located(ScenarioEntity entity, String flow) {
    }

    private Optional<Located> locate(String app, String key, String path) {
        if (path == null || !path.contains("/")) {
            return Optional.empty();
        }
        String flow = path.substring(0, path.indexOf('/'));
        String fileName = path.substring(path.indexOf('/') + 1);
        return screen(app, key, flow)
                .flatMap(screen -> scenarios.findByScreenIdAndFileName(screen.getId(), fileName))
                .map(entity -> new Located(entity, flow));
    }

    private Optional<ScreenEntity> screen(String app, String key, String flow) {
        return menus.findByAppAndProductKey(app, key)
                .flatMap(menu -> screens.findByMenuIdAndFlow(menu.getId(), flow));
    }

    private Menu menuOf(MainMenuEntity menu) {
        List<Screen> list = screens.findByMenuIdOrderBySortOrderAscFlowAsc(menu.getId()).stream()
                .map(screen -> new Screen(screen.getId(), screen.getFlow(), screen.getName()))
                .toList();
        return new Menu(menu.getId(), menu.getApp(), menu.getProductKey(), menu.getCategory(),
                menu.getProductName(), menu.getDescription() == null ? "" : menu.getDescription(), list);
    }

    private static Stored stored(ScenarioEntity entity, String flow) {
        return new Stored(entity.getId(), entity.getVersion() == null ? 0 : entity.getVersion(),
                flow, entity.getFileName(), fieldsOf(entity), entity.getSourcePath());
    }

    private static ScenarioFields fieldsOf(ScenarioEntity entity) {
        return new ScenarioFields(entity.getTitle(), lines(entity.getRules()), lines(entity.getNeeds()),
                lines(entity.getOtherTags()), lines(entity.getPreconditions()), lines(entity.getSteps()),
                lines(entity.getExpected()), entity.getGherkin());
    }

    private static void write(ScenarioEntity entity, ScenarioFields fields) {
        entity.setContent(fields.title(), joined(fields.rules()), joined(fields.needs()),
                joined(fields.otherTags()), joined(fields.preconditions()), joined(fields.steps()),
                joined(fields.expected()), fields.gherkin());
    }

    /** One item per line; an item never holds a line break of its own. */
    private static String joined(List<String> items) {
        return items.stream().map(item -> item.replaceAll("\\R", " ")).collect(Collectors.joining("\n"));
    }

    private static List<String> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(List.of(text.split("\\R")));
    }
}
