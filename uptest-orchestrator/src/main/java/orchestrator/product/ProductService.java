package orchestrator.product;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Serves a banking product: its menu, screens and scenarios from the
 * database, its rules and free text from files.
 *
 * <p><b>Two sources, split by who writes them.</b> The Kayıtlı Senaryolar
 * tree -- ana menü, screens, scenarios -- is edited from the UI all day, so it
 * lives in the database ({@link ScenarioStore}); a save there no longer
 * rewrites a file in the checkout. The rules, test data and the analysts'
 * notes are written by hand and reviewed through git, so they stay on disk:
 *
 * <pre>
 * products/&lt;app&gt;/&lt;product&gt;/
 *   rules.yaml          the oracle
 *   test-data.yaml      what a scenario's @needs resolves to
 *   flow.md             state machine, free text
 *   roles.md            who may do what, free text
 *   open-questions.md   shown to the analyst, never to the model
 *   known-issues.md
 * </pre>
 *
 * {@link #load} joins the two into one {@link ProductModel}, so the validator,
 * the prompt and every other reader see a product exactly as before.
 *
 * <p>product.yaml and the scenario files are read ONCE, by {@link
 * #importMissing}, for any product the database does not know yet; after that
 * the database is the source. {@link #export} writes the scenarios back out
 * as .feature files for git.
 *
 * Deliberately a SIBLING of {@code CatalogService} rather than an extension of
 * it: catalogs are addressed as {@code <app>/<flow>} by {@code Origin}, which
 * travels on every request and is persisted in TEST_RUN's own columns.
 */
@Service
public class ProductService {

    /**
     * Names come from an HTTP request and are used to build a path, so they
     * are restricted to a conservative alphabet rather than sanitised
     * afterwards -- a "../.." would otherwise hand out any file the process
     * can read.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final String PRODUCT_FILE = "product.yaml";
    private static final String RULES_FILE = "rules.yaml";
    private static final String TEST_DATA_FILE = "test-data.yaml";
    private static final String FLOW_FILE = "flow.md";
    private static final String ROLES_FILE = "roles.md";
    private static final String QUESTIONS_FILE = "open-questions.md";
    private static final String ISSUES_FILE = "known-issues.md";

    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    private final Path root;
    private final ScenarioStore store;

    public ProductService(@Value("${uptest.products.dir:./products}") String dir, ScenarioStore store) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.store = store;
    }

    /** One product: identity from the database, rules from its folder. */
    public Optional<ProductModel> load(String app, String product) {
        if (!SAFE_NAME.matcher(app == null ? "" : app).matches()
                || !SAFE_NAME.matcher(product == null ? "" : product).matches()) {
            return Optional.empty();
        }
        return store.menu(safe(app), product).flatMap(this::model);
    }

    /** Every product the database knows, in menu order. */
    public List<ProductModel> list() {
        return store.menus().stream()
                .map(this::model)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Why a product did not load, for the one analyst staring at a picker that
     * does not list the product they expected.
     */
    public String diagnose(String app, String product) {
        if (store.menu(safe(app), product == null ? "" : product).isEmpty()) {
            return safe(app) + "/" + product + " diye bir ana menü veritabanında kayıtlı değil.";
        }
        return folder(app, product)
                .map(folder -> ProductModel.diagnoseRules(text(folder.resolve(RULES_FILE))))
                .orElse("products/" + safe(app) + "/" + product
                        + " klasörü yok -- ürünün kural dosyası tanımlanmamış.");
    }

    /**
     * The scenarios registered under one product, as the table lists them.
     *
     * Each row is derived by composing the scenario back into a .feature and
     * running the same Gherkin parse the file-based registry ran, so a
     * scenario whose Gherkin no longer parses shows up as broken rather than
     * disappearing.
     */
    public List<ScenarioCard> scenarios(String app, String product) {
        return registered(app, product).stream().map(Registered::card).toList();
    }

    /** A table row: what the Gherkin parse says, plus the scenario as stored. */
    public record Registered(ScenarioCard card, ScenarioStore.Stored stored) {

        public ScenarioFields fields() {
            return stored.fields();
        }
    }

    /** {@link #scenarios}, with each scenario as stored beside its card. */
    public List<Registered> registered(String app, String product) {
        return store.scenarios(safe(app), product).stream()
                .map(stored -> new Registered(
                        ScenarioLibrary.parse(stored.path(), stored.flow(),
                                FeatureComposer.compose(stored.fields())),
                        stored))
                .toList();
    }

    /**
     * What this scenario's rules and aliases say NOW that they did not say
     * when it was last reviewed. Empty when nothing moved, and empty when
     * nobody has stamped it — see {@link SourceSnapshot#compare}.
     */
    public List<SourceSnapshot.SourceChange> changes(ScenarioStore.Stored stored,
                                                     ProductModel product) {
        return SourceSnapshot.compare(stored.reviewed(), stored.fields(), product);
    }

    public long scenarioCount(String app, String product) {
        return store.count(safe(app), product);
    }

    /** One scenario as stored -- its fields and version. */
    public Optional<ScenarioStore.Stored> scenario(String app, String product, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        return store.scenario(safe(app), product, path);
    }

    /** One scenario as a whole .feature file -- what a run is given. */
    public Optional<String> scenarioText(String app, String product, String path) {
        return scenario(app, product, path).map(stored -> FeatureComposer.compose(stored.fields()));
    }

    /**
     * An edit from the detail view. Three shapes, and only the fields sent are
     * changed:
     *
     * <ul>
     *   <li>{@code feature}: a whole .feature file, split into fields -- for
     *       clients that still work in files;</li>
     *   <li>{@code gherkin}: the Gherkin half alone. A pasted whole file is
     *       cut back to its Gherkin, so its tag line and description cannot
     *       double up on the natural-language side;</li>
     *   <li>{@code title}/{@code rules}/{@code preconditions}/{@code steps}/
     *       {@code expected}: the natural-language half.</li>
     * </ul>
     *
     * @param version  the version the editor started from; a stale one is a conflict
     * @param reviewed the analyst has read what this scenario's rules and
     *                 aliases say now, so re-stamp it and clear the "kural
     *                 değişti" badge. Absent leaves the stamp alone: a save
     *                 made for some unrelated reason must not clear a badge
     *                 nobody looked at. Sent alone, with every content field
     *                 null, it is the "Gözden geçirildi" button -- the exit
     *                 for a rule that was simply written down wrong, where
     *                 the scenario is right and the run SHOULD go red
     */
    public record ScenarioEdit(String feature, String gherkin, String title, List<String> rules,
                               List<String> preconditions, List<String> steps,
                               List<String> expected, Long version, Boolean reviewed) {
    }

    /** Exactly one of {@code saved} and {@code refused} is non-null. */
    public record SaveResult(ScenarioStore.Stored saved, String refused, boolean conflict) {
    }

    public SaveResult saveScenario(String app, String product, String path, ScenarioEdit edit) {
        if (path == null || path.isBlank() || edit == null) {
            return new SaveResult(null, "Senaryo ve değişiklik gerekli", false);
        }
        String[] refused = new String[1];
        ProductModel model = load(app, product).orElse(null);
        ScenarioStore.Saved result = store.save(safe(app), product, path, edit.version(), current -> {
            ScenarioFields next = merged(current, edit);
            // Refused here, while the edit is still open: a scenario that no
            // longer parses would show up only as a broken row.
            String problem = ScenarioLibrary.parse(path, "", FeatureComposer.compose(next)).problem();
            if (problem != null) {
                refused[0] = "Gherkin okunamadı: " + problem;
                return null;
            }
            return next;
        }, Boolean.TRUE.equals(edit.reviewed())
                ? written -> SourceSnapshot.capture(written, model)
                : null);
        return switch (result.outcome()) {
            case NOT_FOUND -> new SaveResult(null, "Senaryo bulunamadı: " + path, false);
            case CONFLICT -> new SaveResult(null,
                    "Bu senaryo siz düzenlerken başka biri tarafından kaydedildi. "
                            + "Sayfayı yenileyip değişikliğinizi tekrar yapın.", true);
            case SAVED -> refused[0] != null
                    ? new SaveResult(null, refused[0], false)
                    : new SaveResult(result.scenario(), null, false);
        };
    }

    static ScenarioFields merged(ScenarioFields current, ScenarioEdit edit) {
        if (edit.feature() != null && !edit.feature().isBlank()) {
            return ScenarioDocParser.parse(edit.feature());
        }
        String gherkin = current.gherkin();
        String title = current.title();
        if (edit.gherkin() != null) {
            gherkin = ScenarioDocParser.parse(edit.gherkin()).gherkin();
            String named = FeatureComposer.titleOf(gherkin);
            if (!named.isBlank()) {
                title = named;
            }
        }
        if (edit.title() != null && !edit.title().isBlank()) {
            title = edit.title().trim();
            gherkin = FeatureComposer.renamed(gherkin, title);
        }
        return new ScenarioFields(
                title,
                edit.rules() != null ? edit.rules() : current.rules(),
                current.needs(),
                current.otherTags(),
                edit.preconditions() != null ? edit.preconditions() : current.preconditions(),
                edit.steps() != null ? edit.steps() : current.steps(),
                edit.expected() != null ? edit.expected() : current.expected(),
                gherkin);
    }

    /** The created thing's key, or why it was refused. Exactly one is non-null. */
    public record Created(String key, String refused) {
    }

    /**
     * A new "ana menü". Only a database row: its rule files are optional and,
     * when someone writes them, go under {@code products/<app>/<key>/}.
     */
    public Created createProduct(String app, String name) {
        String cleanApp = safe(app);
        if (!cleanApp.equals("ngc") && !cleanApp.equals("ngi")) {
            return new Created(null, "Uygulama NGC ya da NGI olmalı");
        }
        String cleanName = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (cleanName.isEmpty() || cleanName.length() > 60) {
            return new Created(null, "Ana menü adı 1-60 karakter olmalı");
        }
        String key = slug(cleanName);
        if (key.isEmpty() || !SAFE_NAME.matcher(key).matches()) {
            return new Created(null, "Bu addan bir anahtar çıkarılamadı: " + cleanName);
        }
        boolean taken = store.menus().stream().anyMatch(menu ->
                menu.category().toLowerCase(TR).equals(cleanName.toLowerCase(TR)));
        if (taken || store.menu(cleanApp, key).isPresent()) {
            return new Created(null, "Bu ana menü zaten var: " + cleanName);
        }
        store.createMenu(cleanApp, key, cleanName, cleanName, "");
        return new Created(key, null);
    }

    /** A new screen under an ana menü. */
    public Created addScreen(String app, String product, String name) {
        Optional<ScenarioStore.Menu> menu = store.menu(safe(app), product == null ? "" : product);
        if (menu.isEmpty()) {
            return new Created(null, "Ana menü bulunamadı: " + app + "/" + product);
        }
        String cleanName = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (cleanName.isEmpty() || cleanName.length() > 60) {
            return new Created(null, "Ekran adı 1-60 karakter olmalı");
        }
        String flow = slug(cleanName);
        if (flow.isEmpty() || !SAFE_NAME.matcher(flow).matches()) {
            return new Created(null, "Bu addan ekran adı çıkarılamadı: " + cleanName);
        }
        if (menu.get().screens().stream().anyMatch(screen -> screen.flow().equals(flow))) {
            return new Created(null, "Bu ekran zaten var: " + cleanName);
        }
        return store.addScreen(safe(app), product, flow, cleanName)
                .map(screen -> new Created(screen.flow(), null))
                .orElse(new Created(null, "Ana menü bulunamadı: " + app + "/" + product));
    }

    /**
     * Moves a scenario to another screen -- of this product or of another
     * product of the SAME application, since a scenario logs into one app.
     *
     * @return the moved scenario's new path
     */
    public Created moveScenario(String app, String product, String path,
                                String toProduct, String toFlow) {
        if (toProduct == null || !SAFE_NAME.matcher(toProduct).matches()
                || toFlow == null || !SAFE_NAME.matcher(toFlow).matches()) {
            return new Created(null, "Geçersiz ekran: " + toFlow);
        }
        return store.move(safe(app), product, path, toProduct, toFlow);
    }

    /**
     * A NEW scenario on a screen, named the way the files were: the next
     * two-digit number on that screen plus the title's slug
     * ("10-yeni-liste-iptal.feature"). Refuses text that does not parse.
     *
     * @return the new scenario's path
     */
    public Created createScenario(String app, String product, String flow, String text) {
        Optional<ScenarioStore.Menu> menu = store.menu(safe(app), product == null ? "" : product);
        if (menu.isEmpty()) {
            return new Created(null, "Ürün bulunamadı: " + app + "/" + product);
        }
        if (flow == null || menu.get().screens().stream().noneMatch(screen -> screen.flow().equals(flow))) {
            return new Created(null, "Ekran tanımlı değil: " + flow);
        }
        if (text == null || text.isBlank()) {
            return new Created(null, "Senaryo metni boş");
        }
        ScenarioCard card = ScenarioLibrary.parse("yeni.feature", flow, text);
        if (card.problem() != null) {
            return new Created(null, "Gherkin okunamadı: " + card.problem());
        }
        ScenarioFields fields = ScenarioDocParser.parse(text);
        String name = slug(fields.title());
        if (name.isEmpty()) {
            return new Created(null, "Senaryo adı gerekli");
        }

        List<String> taken = store.fileNames(safe(app), product, flow);
        int next = taken.stream()
                .filter(n -> n.matches("\\d+-.*\\.feature"))
                .mapToInt(n -> Integer.parseInt(n.substring(0, n.indexOf('-'))))
                .max().orElse(0) + 1;
        String fileName = String.format("%02d-%s.feature",
                next, name.length() > 50 ? name.substring(0, 50) : name);
        if (taken.contains(fileName)) {
            return new Created(null, "Bu adla bir senaryo zaten var: " + fileName);
        }
        // Stamped on creation: whoever is writing this scenario is reading
        // the rules as they stand, so that IS the reviewed state.
        List<SourceSnapshot.Source> reviewed =
                SourceSnapshot.capture(fields, load(app, product).orElse(null));
        return store.createScenario(safe(app), product, flow, fileName, fields, null, reviewed)
                .map(stored -> new Created(stored.path(), null))
                .orElse(new Created(null, "Ekran tanımlı değil: " + flow));
    }

    /* ------------------------- import / export --------------------------- */

    /** What {@link #importMissing} did, for the startup log. */
    public record Imported(int menus, int screens, int scenarios, List<String> skipped) {
    }

    /**
     * Copies into the database every product folder it does not know yet:
     * product.yaml becomes the menu and its screens, each
     * {@code scenarios/<screen>/*.feature} a scenario.
     *
     * Only MISSING products, never a refresh: once a product is in the
     * database the database is the source, and re-reading the files would
     * undo every edit made since. Files are read, never changed or deleted.
     * {@code scenarios-gizli/} is not read -- what was hidden stays hidden.
     */
    public Imported importMissing() {
        int menus = 0;
        int screens = 0;
        int scenarios = 0;
        List<String> skipped = new ArrayList<>();
        for (Path folder : productFolders()) {
            String app = folder.getParent().getFileName().toString().toLowerCase(Locale.ROOT);
            String key = folder.getFileName().toString();
            if (store.menu(app, key).isPresent()) {
                continue;
            }
            Optional<ProductModel> model = ProductModel.parse(app, key, read(folder, null));
            if (model.isEmpty()) {
                skipped.add(app + "/" + key + ": " + ProductModel.diagnose(text(folder.resolve(PRODUCT_FILE))));
                continue;
            }
            ProductModel product = model.get();
            store.createMenu(app, key,
                    product.category().isBlank() ? product.name() : product.category(),
                    product.name(), product.description());
            menus++;

            // Declared screens first, in the declared order, then any folder
            // that holds scenarios nobody declared -- the same union the
            // file-based tree showed.
            Path scenariosDir = folder.resolve(ScenarioLibrary.SCENARIOS_DIR);
            LinkedHashSet<String> flows = new LinkedHashSet<>(product.flows());
            flows.addAll(foldersWithScenarios(scenariosDir));
            for (String flow : flows) {
                if (!SAFE_NAME.matcher(flow).matches()) {
                    skipped.add(app + "/" + key + "/" + flow + ": ekran adı geçersiz");
                    continue;
                }
                store.addScreen(app, key, flow, product.screenName(flow));
                screens++;
                for (Path file : features(scenariosDir.resolve(flow))) {
                    String source = root.relativize(file).toString().replace('\\', '/');
                    ScenarioFields fields = ScenarioDocParser.parse(text(file));
                    // Stamped with rules.yaml AS IT IS IN THIS COMMIT. The
                    // scenario files and the rule file travelled here
                    // together, so as far as anyone can know they agree.
                    // Leaving them unstamped would instead light the "kural
                    // değişti" badge on every row at first start, and a badge
                    // that is on for everything says nothing about anything.
                    store.createScenario(app, key, flow, file.getFileName().toString(), fields,
                            "products/" + source, SourceSnapshot.capture(fields, product));
                    scenarios++;
                }
            }
            for (Path loose : features(scenariosDir)) {
                skipped.add(root.relativize(loose) + ": bir ekran klasöründe değil, aktarılmadı");
            }
        }
        return new Imported(menus, screens, scenarios, skipped);
    }

    /**
     * Stamps scenarios that were in the database before stamping existed, so
     * the "kural değişti" badge works for them too. Runs once per scenario:
     * a stamped one is never touched again.
     *
     * @return how many were stamped
     */
    public int stampUnreviewed() {
        int stamped = 0;
        for (ScenarioStore.Menu menu : store.menus()) {
            ProductModel product = model(menu).orElse(null);
            if (product == null) {
                continue;
            }
            stamped += store.stampUnreviewed(menu.app(), menu.key(),
                    fields -> SourceSnapshot.capture(fields, product));
        }
        return stamped;
    }

    /** What {@link #export} did. */
    public record Exported(int written, int unchanged, List<String> notInDatabase) {
    }

    /**
     * Writes every scenario out as
     * {@code products/<app>/<key>/scenarios/<screen>/<file>} so the
     * database's content can be committed.
     *
     * Write-only. A file under scenarios/ that the database no longer has --
     * a scenario moved to another screen, say -- is REPORTED, never deleted:
     * deleting files is a decision for a person. product.yaml is written only
     * when the folder has none, because the existing ones carry hand-written
     * comments a regenerated file would erase.
     */
    public Exported export() {
        int written = 0;
        int unchanged = 0;
        Set<Path> known = new HashSet<>();
        List<Path> folders = new ArrayList<>();
        for (ScenarioStore.Menu menu : store.menus()) {
            Path folder = root.resolve(menu.app()).resolve(menu.key()).normalize();
            if (!folder.startsWith(root)) {
                continue;
            }
            folders.add(folder);
            try {
                Files.createDirectories(folder);
                Path productFile = folder.resolve(PRODUCT_FILE);
                if (!Files.exists(productFile)) {
                    Files.writeString(productFile, productYaml(menu), StandardCharsets.UTF_8);
                    written++;
                }
                for (ScenarioStore.Stored stored : store.scenarios(menu.app(), menu.key())) {
                    Path file = folder.resolve(ScenarioLibrary.SCENARIOS_DIR)
                            .resolve(stored.flow()).resolve(stored.fileName()).normalize();
                    if (!file.startsWith(folder)) {
                        continue;
                    }
                    known.add(file);
                    String content = FeatureComposer.compose(stored.fields());
                    if (Files.isRegularFile(file) && text(file).equals(content)) {
                        unchanged++;
                        continue;
                    }
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                    written++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot export into " + folder, e);
            }
        }
        List<String> stale = new ArrayList<>();
        for (Path folder : folders) {
            Path scenariosDir = folder.resolve(ScenarioLibrary.SCENARIOS_DIR);
            if (!Files.isDirectory(scenariosDir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(scenariosDir)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".feature"))
                        .filter(path -> !known.contains(path.normalize()))
                        .sorted()
                        .forEach(path -> stale.add(root.relativize(path).toString().replace('\\', '/')));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list " + scenariosDir, e);
            }
        }
        return new Exported(written, unchanged, stale);
    }

    /* ------------------------------ helpers ------------------------------ */

    private Optional<ProductModel> model(ScenarioStore.Menu menu) {
        Path folder = root.resolve(menu.app()).resolve(menu.key()).normalize();
        boolean present = folder.startsWith(root) && Files.isDirectory(folder);
        return ProductModel.parse(menu.app(), menu.key(), read(present ? folder : null, productYaml(menu)));
    }

    /**
     * The menu as product.yaml text, so {@link ProductModel#parse} -- and with
     * it every reader of a product -- stays exactly as it was.
     */
    private static String productYaml(ScenarioStore.Menu menu) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("app", menu.app());
        document.put("product", menu.key());
        document.put("name", menu.name());
        document.put("category", menu.category());
        document.put("description", menu.description());
        document.put("flows", menu.screens().stream().map(ScenarioStore.Screen::flow).toList());
        Map<String, String> names = new LinkedHashMap<>();
        menu.screens().forEach(screen -> names.put(screen.flow(), screen.name()));
        document.put("screen_names", names);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        return new Yaml(options).dump(document);
    }

    /** Folders products/&lt;app&gt;/&lt;product&gt;/ that carry a product.yaml. */
    private List<Path> productFolders() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.walk(root, 2)) {
            return folders.filter(Files::isDirectory)
                    .filter(path -> path.getParent() != null && !path.equals(root)
                            && !path.getParent().equals(root))
                    .filter(path -> Files.isRegularFile(path.resolve(PRODUCT_FILE)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list products under " + root, e);
        }
    }

    private static List<String> foldersWithScenarios(Path scenariosDir) {
        if (!Files.isDirectory(scenariosDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(scenariosDir)) {
            return children.filter(Files::isDirectory)
                    .filter(dir -> !features(dir).isEmpty())
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + scenariosDir, e);
        }
    }

    /** The .feature files directly inside one folder, by name. */
    private static List<Path> features(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".feature"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }

    /** Turkish-aware ASCII fold for a key: "Nakit Yönetimi" -> "nakit-yonetimi". */
    static String slug(String text) {
        String lower = text.toLowerCase(TR);
        StringBuilder out = new StringBuilder();
        for (char c : lower.toCharArray()) {
            out.append(switch (c) {
                case 'ç' -> 'c';
                case 'ğ' -> 'g';
                case 'ı' -> 'i';
                case 'ö' -> 'o';
                case 'ş' -> 's';
                case 'ü' -> 'u';
                default -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '-';
            });
        }
        String folded = out.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return folded.length() > 64 ? folded.substring(0, 64) : folded;
    }

    public Path root() {
        return root;
    }

    private Optional<Path> folder(String app, String product) {
        if (!SAFE_NAME.matcher(app == null ? "" : app).matches()
                || !SAFE_NAME.matcher(product == null ? "" : product).matches()) {
            return Optional.empty();
        }
        Path folder = root.resolve(safe(app)).resolve(product).normalize();
        return folder.startsWith(root) && Files.isDirectory(folder)
                ? Optional.of(folder)
                : Optional.empty();
    }

    /**
     * The product's files as text. {@code productYaml} replaces the file's
     * product.yaml when given -- the database's version of the identity; a
     * null folder means "no files", and every file reads as "".
     */
    private static ProductModel.Sources read(Path folder, String productYaml) {
        if (folder == null) {
            return new ProductModel.Sources(productYaml, "", "", "", "", "", "");
        }
        return new ProductModel.Sources(
                productYaml != null ? productYaml : text(folder.resolve(PRODUCT_FILE)),
                text(folder.resolve(RULES_FILE)),
                text(folder.resolve(TEST_DATA_FILE)),
                text(folder.resolve(FLOW_FILE)),
                text(folder.resolve(ROLES_FILE)),
                text(folder.resolve(QUESTIONS_FILE)),
                text(folder.resolve(ISSUES_FILE)));
    }

    /**
     * A file's contents, or "" when it is not there: every file but
     * product.yaml is optional by design.
     */
    private static String text(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static String safe(String app) {
        return app == null ? "" : app.toLowerCase(Locale.ROOT);
    }
}
