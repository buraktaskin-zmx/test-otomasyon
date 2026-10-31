package orchestrator.product;

import orchestrator.persistence.MainMenuEntity;
import orchestrator.persistence.MainMenuRepository;
import orchestrator.persistence.ScenarioEntity;
import orchestrator.persistence.ScenarioHeadingEntity;
import orchestrator.persistence.ScenarioHeadingRepository;
import orchestrator.persistence.ScenarioRepository;
import orchestrator.persistence.ScreenEntity;
import orchestrator.persistence.ScreenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * @param headingId the screen's "Başlık" it is grouped under; null when
     *                  it has none, and also when the stored id names no
     *                  heading of its own screen -- an id left dangling
     *                  reads as "başlıksız", never as an error
     */
    public record Stored(UUID id, long version, String flow, String fileName,
                         ScenarioFields fields, String sourcePath,
                         List<SourceSnapshot.Source> reviewed, Instant reviewedAt,
                         UUID headingId) {

        public String path() {
            return flow + "/" + fileName;
        }
    }

    /** A "Başlık" on one screen. */
    public record Heading(UUID id, String flow, String name) {
    }

    /** {@link #addHeading}: the heading, and whether it was already there. */
    public record HeadingAdded(Heading heading, boolean existed) {
    }

    public enum Outcome { SAVED, NOT_FOUND, CONFLICT }

    public record Saved(Outcome outcome, Stored scenario) {
    }

    private final MainMenuRepository menus;
    private final ScreenRepository screens;
    private final ScenarioRepository scenarios;
    private final ScenarioHeadingRepository headings;

    /** The Boot-managed Jackson mapper, injected as RunStore's is. */
    private final ObjectMapper mapper;

    public ScenarioStore(MainMenuRepository menus, ScreenRepository screens,
                         ScenarioRepository scenarios, ScenarioHeadingRepository headings,
                         ObjectMapper mapper) {
        this.menus = menus;
        this.screens = screens;
        this.scenarios = scenarios;
        this.headings = headings;
        this.mapper = mapper;
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
        Map<UUID, UUID> headingScreens = headings.findByScreenIdInOrderByCreatedAtAscIdAsc(flows.keySet())
                .stream().collect(Collectors.toMap(ScenarioHeadingEntity::getId,
                        ScenarioHeadingEntity::getScreenId));
        return scenarios.findByScreenIdInOrderByFileNameAsc(flows.keySet()).stream()
                .map(entity -> stored(entity, flows.get(entity.getScreenId()),
                        ownHeading(entity, headingScreens)))
                .sorted((a, b) -> a.path().compareTo(b.path()))
                .toList();
    }

    /** Every screen's headings in one menu, each screen's in the order they were made. */
    @Transactional(readOnly = true)
    public List<Heading> headings(String app, String key) {
        Optional<MainMenuEntity> menu = menus.findByAppAndProductKey(app, key);
        if (menu.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> flows = screens.findByMenuIdOrderBySortOrderAscFlowAsc(menu.get().getId())
                .stream().collect(Collectors.toMap(ScreenEntity::getId, ScreenEntity::getFlow));
        if (flows.isEmpty()) {
            return List.of();
        }
        return headings.findByScreenIdInOrderByCreatedAtAscIdAsc(flows.keySet()).stream()
                .map(heading -> new Heading(heading.getId(), flows.get(heading.getScreenId()),
                        heading.getName()))
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

    /**
     * A heading on one screen. A name already there -- compared with case
     * and the Turkish I/İ/ı ignored -- is handed back instead of a second one
     * being made.
     *
     * @return empty when the screen does not exist
     */
    @Transactional
    public Optional<HeadingAdded> addHeading(String app, String key, String flow, String name) {
        return screen(app, key, flow).map(screen -> {
            String wanted = headingKey(name);
            for (ScenarioHeadingEntity existing : headings.findByScreenIdOrderByCreatedAtAscIdAsc(screen.getId())) {
                if (headingKey(existing.getName()).equals(wanted)) {
                    return new HeadingAdded(new Heading(existing.getId(), flow, existing.getName()), true);
                }
            }
            ScenarioHeadingEntity saved = headings.save(new ScenarioHeadingEntity(screen.getId(), name));
            return new HeadingAdded(new Heading(saved.getId(), flow, saved.getName()), false);
        });
    }

    /**
     * Groups a scenario under one of its OWN screen's headings, or under none.
     *
     * @param headingId null for "Başlıksız"
     * @return the scenario's path, or null with {@code refused} explaining why
     */
    @Transactional
    public ProductService.Created assignHeading(String app, String key, String path, UUID headingId) {
        Optional<Located> found = locate(app, key, path);
        if (found.isEmpty()) {
            return new ProductService.Created(null, "Senaryo bulunamadı: " + path);
        }
        ScenarioEntity entity = found.get().entity;
        if (headingId != null) {
            Optional<ScenarioHeadingEntity> heading = headings.findById(headingId);
            if (heading.isEmpty() || !heading.get().getScreenId().equals(entity.getScreenId())) {
                return new ProductService.Created(null, "Bu ekranda böyle bir başlık yok");
            }
        }
        entity.setHeadingId(headingId);
        entity.touch();
        return new ProductService.Created(path, null);
    }

    /**
     * The form a heading name is compared in: spaces collapsed, lower case,
     * and I/İ/ı/i all one letter. "Fatura İptal", "FATURA IPTAL" and
     * "fatura iptal" are the same heading.
     */
    static String headingKey(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.forLanguageTag("tr-TR"))
                .replace('ı', 'i');
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

    /**
     * @param reviewed what this scenario's rules and aliases say right now,
     *                 stamped because whoever is writing the scenario is
     *                 looking at them. Null leaves it unstamped
     * @return the new scenario, or empty when the screen does not exist
     */
    @Transactional
    public Optional<Stored> createScenario(String app, String key, String flow, String fileName,
                                           ScenarioFields fields, String sourcePath,
                                           List<SourceSnapshot.Source> reviewed) {
        return createScenario(app, key, flow, fileName, fields, sourcePath, reviewed, null);
    }

    /**
     * {@link #createScenario(String, String, String, String, ScenarioFields, String, List)},
     * grouped under {@code headingId} -- which must be a heading of that
     * screen, or it is ignored and the scenario starts out başlıksız.
     */
    @Transactional
    public Optional<Stored> createScenario(String app, String key, String flow, String fileName,
                                           ScenarioFields fields, String sourcePath,
                                           List<SourceSnapshot.Source> reviewed, UUID headingId) {
        return screen(app, key, flow).map(screen -> {
            ScenarioEntity entity = new ScenarioEntity(screen.getId(), fileName, sourcePath);
            if (headingId != null && headings.findById(headingId)
                    .filter(heading -> heading.getScreenId().equals(screen.getId())).isPresent()) {
                entity.setHeadingId(headingId);
            }
            write(entity, fields);
            if (reviewed != null) {
                entity.setReviewedSources(toJson(reviewed));
            }
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
     * @param stamp           given the fields as finally written, what its
     *                        rules and aliases say right now. Null means
     *                        LEAVE THE STAMP ALONE -- an unrelated edit must
     *                        not clear a "this rule changed" badge the analyst
     *                        has not looked at
     */
    @Transactional
    public Saved save(String app, String key, String path, Long expectedVersion,
                      Function<ScenarioFields, ScenarioFields> change,
                      Function<ScenarioFields, List<SourceSnapshot.Source>> stamp) {
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
        if (stamp != null) {
            // Stamped from the fields as WRITTEN, not as they were: the edit
            // may have added or dropped a rule tag.
            entity.setReviewedSources(toJson(stamp.apply(next)));
        }
        // Flushed here, not at commit, so the version handed back is the new one.
        ScenarioEntity saved = scenarios.saveAndFlush(entity);
        return new Saved(Outcome.SAVED, stored(saved, found.get().flow));
    }

    /**
     * Stamps every scenario of one product that has never been stamped.
     *
     * For the database that already existed when this feature arrived:
     * ddl-auto=update ADDS the two columns and leaves every row null, so
     * without this nothing would ever go stale for the scenarios that were
     * there first -- the badge would work only for scenarios written
     * afterwards, which is the sort of half-on that is worse than off.
     *
     * Same reading as the import: the scenarios and the rule file are as
     * they are right now, and nobody has said they disagree.
     *
     * @return how many were stamped
     */
    @Transactional
    public int stampUnreviewed(String app, String key,
                               Function<ScenarioFields, List<SourceSnapshot.Source>> stamp) {
        Optional<MainMenuEntity> menu = menus.findByAppAndProductKey(app, key);
        if (menu.isEmpty()) {
            return 0;
        }
        int stamped = 0;
        for (ScreenEntity screen : screens.findByMenuIdOrderBySortOrderAscFlowAsc(menu.get().getId())) {
            for (ScenarioEntity entity : scenarios.findByScreenIdOrderByFileNameAsc(screen.getId())) {
                if (entity.getReviewedAt() != null) {
                    continue;
                }
                entity.setReviewedSources(toJson(stamp.apply(fieldsOf(entity))));
                stamped++;
            }
        }
        return stamped;
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
        // Headings belong to a screen: the old one's means nothing here.
        entity.setHeadingId(null);
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

    private Stored stored(ScenarioEntity entity, String flow) {
        UUID heading = entity.getHeadingId() == null ? null : headings.findById(entity.getHeadingId())
                .filter(found -> found.getScreenId().equals(entity.getScreenId()))
                .map(ScenarioHeadingEntity::getId)
                .orElse(null);
        return stored(entity, flow, heading);
    }

    private Stored stored(ScenarioEntity entity, String flow, UUID heading) {
        return new Stored(entity.getId(), entity.getVersion() == null ? 0 : entity.getVersion(),
                flow, entity.getFileName(), fieldsOf(entity), entity.getSourcePath(),
                fromJson(entity.getReviewedSources()), entity.getReviewedAt(), heading);
    }

    /** The scenario's heading when it is one of its own screen's, else null. */
    private static UUID ownHeading(ScenarioEntity entity, Map<UUID, UUID> headingScreens) {
        UUID heading = entity.getHeadingId();
        return heading != null && entity.getScreenId().equals(headingScreens.get(heading)) ? heading : null;
    }

    private static final TypeReference<List<SourceSnapshot.Source>> SOURCES =
            new TypeReference<>() {
            };

    private String toJson(List<SourceSnapshot.Source> sources) {
        return mapper.writeValueAsString(sources == null ? List.of() : sources);
    }

    /**
     * An unreadable stamp reads as NO stamp, never as a failure.
     *
     * The column is written by this class alone, so a value it cannot parse
     * means the shape changed under an existing database -- and the honest
     * answer then is "nobody has reviewed this against the current shape",
     * which is exactly what an empty list says. Refusing to serve the
     * scenario would take the whole Kayıtlı Senaryolar screen down over a
     * badge.
     */
    private List<SourceSnapshot.Source> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, SOURCES);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
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
