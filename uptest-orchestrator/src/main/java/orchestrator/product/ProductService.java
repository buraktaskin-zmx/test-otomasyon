package orchestrator.product;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Serves the written context for a banking product.
 *
 * Deliberately a SIBLING of {@code CatalogService} rather than an extension of
 * it. Catalogs are addressed as {@code <app>/<flow>} by {@code Origin}, which
 * travels on every validate, suggest and run request and is persisted in
 * {@code TestRunEntity}'s own columns; adding a third path segment there would
 * mean touching the wire format, the database and three places in the UI that
 * split that string by hand. A product lives in its own tree and names the
 * flows it owns, so nothing about how a catalog is addressed changes.
 *
 * <pre>
 * products/&lt;app&gt;/&lt;product&gt;/
 *   product.yaml        name, app, the flows it claims
 *   rules.yaml          the oracle
 *   flow.md             state machine, free text
 *   roles.md            who may do what, free text
 *   open-questions.md   shown to the analyst, never to the model
 * </pre>
 */
@Service
public class ProductService {

    /**
     * Names come from an HTTP request and are used to build a path, so they
     * are restricted to a conservative alphabet rather than sanitised
     * afterwards — the same containment reasoning {@code CatalogService} spells
     * out: a "../.." would otherwise hand out any file the process can read.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final String PRODUCT_FILE = "product.yaml";
    private static final String RULES_FILE = "rules.yaml";
    private static final String TEST_DATA_FILE = "test-data.yaml";
    private static final String FLOW_FILE = "flow.md";
    private static final String ROLES_FILE = "roles.md";
    private static final String QUESTIONS_FILE = "open-questions.md";
    private static final String ISSUES_FILE = "known-issues.md";

    private final Path root;

    public ProductService(@Value("${uptest.products.dir:./products}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** One product, or empty when there is no such directory or no product.yaml. */
    public Optional<ProductModel> load(String app, String product) {
        return folder(app, product).flatMap(folder ->
                ProductModel.parse(app.toLowerCase(Locale.ROOT), product, read(folder)));
    }

    /**
     * Every product on disk.
     *
     * Products that will not parse are left OUT rather than reported here.
     * This feeds a dropdown, and a half-read product in a picker is worse than
     * an absent one — {@link #diagnose} is how an analyst finds out why
     * something they expected is missing.
     */
    public List<ProductModel> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<ProductModel> products = new ArrayList<>();
        // Depth 2: products/<app>/<product>. The files themselves sit one
        // level deeper, which is the difference from CatalogService.list()
        // and the reason this walks directories rather than *.yaml files.
        try (Stream<Path> folders = Files.walk(root, 2)) {
            folders.filter(Files::isDirectory)
                    .filter(path -> path.getParent() != null && !path.equals(root)
                            && !path.getParent().equals(root))
                    .filter(path -> Files.isRegularFile(path.resolve(PRODUCT_FILE)))
                    .sorted()
                    .forEach(path -> ProductModel.parse(
                            path.getParent().getFileName().toString(),
                            path.getFileName().toString(),
                            read(path)).ifPresent(products::add));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list products under " + root, e);
        }
        return List.copyOf(products);
    }

    /**
     * Why a product did not load, for the one analyst staring at a picker that
     * does not list the product they just wrote. Reads the files again, which
     * is affordable because nothing calls this on the happy path.
     */
    public String diagnose(String app, String product) {
        Optional<Path> folder = folder(app, product);
        if (folder.isEmpty()) {
            return "products/" + safe(app) + "/" + product + " diye bir klasör yok.";
        }
        ProductModel.Sources sources = read(folder.get());
        String identity = ProductModel.diagnose(sources.productYaml());
        return identity.startsWith("product.yaml okunabiliyor")
                ? ProductModel.diagnoseRules(sources.rulesYaml())
                : identity;
    }

    /**
     * The scenarios registered under one product, read off disk every time.
     *
     * No cache: a .feature file is edited by hand in an IDE while this screen
     * is open, and a registry that showed yesterday's step count would be
     * worse than no registry at all. The cost is a directory walk and a
     * Gherkin parse per file — microseconds against a list someone is reading.
     */
    public List<ScenarioCard> scenarios(String app, String product) {
        return folder(app, product).map(ScenarioLibrary::scan).orElse(List.of());
    }

    /** The count alone, for the tree — see {@link ScenarioLibrary#count}. */
    public long scenarioCount(String app, String product) {
        return folder(app, product).map(ScenarioLibrary::count).orElse(0L);
    }

    /**
     * One scenario's Gherkin, or null when the path names nothing inside this
     * product's scenarios directory.
     *
     * The registry lists what a file is ABOUT; this is the file. Clicking a
     * row and reading the steps is the point at which the two columns above
     * stop being claims — the tags say the scenario covers R-AL-011, and
     * here is whether the steps actually do.
     */
    public Optional<String> scenarioText(String app, String product, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        return folder(app, product)
                .map(folder -> ScenarioLibrary.text(folder, path));
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

    private ProductModel.Sources read(Path folder) {
        return new ProductModel.Sources(
                text(folder.resolve(PRODUCT_FILE)),
                text(folder.resolve(RULES_FILE)),
                text(folder.resolve(TEST_DATA_FILE)),
                text(folder.resolve(FLOW_FILE)),
                text(folder.resolve(ROLES_FILE)),
                text(folder.resolve(QUESTIONS_FILE)),
                text(folder.resolve(ISSUES_FILE)));
    }

    /**
     * A file's contents, or "" when it is not there.
     *
     * Only {@code product.yaml} is load-bearing; the other four are optional
     * by design, so "absent" has to be an ordinary value rather than a failure.
     * A product being written has most of them missing and must still load.
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
