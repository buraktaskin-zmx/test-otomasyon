package orchestrator.flow;

import orchestrator.flow.FlowModel.Flow;
import orchestrator.flow.FlowModel.Plan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The drawing on disk, in the product's own folder:
 *
 * <pre>
 * products/&lt;app&gt;/&lt;product&gt;/flow/
 *   flow.json              nodes, edges, the analyst's intent and out-of-scope
 *   plan.yaml              stage 3: the planned scenarios, as JSON despite the name
 *   screens/&lt;id&gt;.png       what was pasted
 *   screens/&lt;id&gt;.read.yaml what the vision model read off it
 * </pre>
 *
 * <p><b>Files rather than the database</b>, unlike the scenario library. Three
 * reasons: the drawing is documentation that replaces {@code flow.md} and
 * belongs beside the rules it is read with; a screenshot is megabytes and H2
 * is not where megabytes should go; and every stage's output has to be
 * readable and fixable by a person, which a BLOB in a table is not.
 *
 * <p><b>The model's first read is kept</b> ({@code .read.yaml}) even after the
 * analyst has corrected it into a catalog. That is the only way to answer
 * "how much did the vision model get wrong on this screen", which decides
 * whether this route is trustworthy at all.
 */
@Service
public class FlowStore {

    /** Same shape ProductService demands, for the same reason: these become paths. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * A single screenshot's ceiling. A full-screen PNG is 200 KB-1 MB; 8 MB is
     * room for a tall page at high DPI and still a clear refusal for someone
     * pasting a photograph.
     */
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    /**
     * Pictures per screen. A long page takes three or four captures; past
     * this it is not one screen any more, and a vision call carrying them all
     * would be tens of thousands of tokens.
     */
    static final int MAX_IMAGES_PER_NODE = 8;

    private final Path root;
    private final ObjectMapper mapper;

    public FlowStore(@Value("${uptest.products.dir:./products}") String dir, ObjectMapper mapper) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    /* ------------------------------ the tree ------------------------------ */

    public Flow read(String app, String product) {
        Path file = folder(app, product).map(f -> f.resolve("flow.json")).orElse(null);
        if (file == null || !Files.isRegularFile(file)) {
            return Flow.empty();
        }
        try {
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Flow.class);
        } catch (RuntimeException | IOException unreadable) {
            // Same leniency as every other reader here: a broken file costs
            // the drawing, never the application.
            System.err.println("[flow] " + file + " okunamadı: " + unreadable);
            return Flow.empty();
        }
    }

    /** @return what was refused, or null when written */
    public String write(String app, String product, Flow flow) {
        Optional<Path> folder = folder(app, product);
        if (folder.isEmpty()) {
            return "Ürün klasörü belirlenemedi: " + app + "/" + product;
        }
        for (FlowModel.Node node : flow.nodes()) {
            if (!SAFE_NAME.matcher(node.id() == null ? "" : node.id()).matches()) {
                return "Geçersiz ekran kimliği: " + node.id();
            }
        }
        try {
            Files.createDirectories(folder.get());
            Files.writeString(folder.get().resolve("flow.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(flow),
                    StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write flow for " + app + "/" + product, e);
        }
    }

    /* ---------------------------- screenshots ---------------------------- */

    /**
     * Stores one node's screenshot.
     *
     * @param dataUrl {@code data:image/png;base64,...} as the browser produced
     *                it — the only way an image can travel, since the UI's HTTP
     *                client sends JSON and nothing else
     * @return what was refused, or null when written
     */
    public String writeScreenshot(String app, String product, String nodeId, String dataUrl) {
        return writeScreenshot(app, product, nodeId, -1, dataUrl);
    }

    /**
     * Stores one of a node's screenshots.
     *
     * @param index which one to write, or negative to APPEND. A banking page
     *              rarely fits in one capture -- the analyst scrolls and
     *              pastes again -- so a screen is a LIST of pictures and the
     *              vision model is given all of them at once.
     * @return what was refused, or null when written
     */
    public String writeScreenshot(String app, String product, String nodeId, int index,
                                  String dataUrl) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return "Geçersiz ekran kimliği: " + nodeId;
        }
        int at = index < 0 ? screenshotCount(app, product, nodeId) : index;
        if (at >= MAX_IMAGES_PER_NODE) {
            return "Bir ekrana en fazla " + MAX_IMAGES_PER_NODE + " görüntü eklenebilir.";
        }
        byte[] png = decode(dataUrl);
        if (png == null) {
            return "Görüntü okunamadı; PNG bekleniyor.";
        }
        if (png.length > MAX_IMAGE_BYTES) {
            return "Görüntü çok büyük (" + (png.length / 1024 / 1024) + " MB). En fazla "
                    + (MAX_IMAGE_BYTES / 1024 / 1024) + " MB.";
        }
        Optional<Path> folder = screensFolder(app, product);
        if (folder.isEmpty()) {
            return "Ürün klasörü belirlenemedi: " + app + "/" + product;
        }
        try {
            Files.createDirectories(folder.get());
            Files.write(folder.get().resolve(shotName(nodeId, at)), png);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write screenshot " + nodeId, e);
        }
    }

    public Optional<byte[]> screenshot(String app, String product, String nodeId) {
        return screenshot(app, product, nodeId, 0);
    }

    public Optional<byte[]> screenshot(String app, String product, String nodeId, int index) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches() || index < 0) {
            return Optional.empty();
        }
        return screensFolder(app, product)
                .map(folder -> folder.resolve(shotName(nodeId, index)))
                .filter(Files::isRegularFile)
                .map(file -> {
                    try {
                        return Files.readAllBytes(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read " + file, e);
                    }
                });
    }

    /** How many pictures each node has, so the UI need not fetch megabytes. */
    public Map<String, Integer> screenshotPresence(String app, String product, Flow flow) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (FlowModel.Node node : flow.nodes()) {
            out.put(node.id(), screenshotCount(app, product, node.id()));
        }
        return out;
    }

    /**
     * How many pictures this node has, counted forward from index 0 until one
     * is missing.
     *
     * <p>Counting rather than listing the directory is what keeps the index
     * space DENSE, and every caller leans on that: the UI asks for
     * {@code ?index=2}, the reader walks 0..n-1. It is also why
     * {@link #deleteScreenshot(String, String, String, int)} closes the gap
     * it makes.
     */
    public int screenshotCount(String app, String product, String nodeId) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return 0;
        }
        Optional<Path> folder = screensFolder(app, product);
        if (folder.isEmpty()) {
            return 0;
        }
        int n = 0;
        while (n < MAX_IMAGES_PER_NODE
                && Files.isRegularFile(folder.get().resolve(shotName(nodeId, n)))) {
            n++;
        }
        return n;
    }

    /** Every picture of one node, in order -- what the vision model is given. */
    public List<byte[]> screenshots(String app, String product, String nodeId) {
        List<byte[]> out = new ArrayList<>();
        int count = screenshotCount(app, product, nodeId);
        for (int i = 0; i < count; i++) {
            screenshot(app, product, nodeId, i).ifPresent(out::add);
        }
        return out;
    }

    /** Every picture of this node, and the draft read off them. */
    public void deleteScreenshot(String app, String product, String nodeId) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return;
        }
        screensFolder(app, product).ifPresent(folder -> {
            try {
                for (int i = 0; i < MAX_IMAGES_PER_NODE; i++) {
                    Files.deleteIfExists(folder.resolve(shotName(nodeId, i)));
                }
                Files.deleteIfExists(folder.resolve(nodeId + ".read.yaml"));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot delete screenshots " + nodeId, e);
            }
        });
    }

    /**
     * One picture, with the ones after it shuffled down to close the gap.
     *
     * <p>The renumbering is the point. Leave a hole at index 1 of three and
     * {@link #screenshotCount} stops at 1: the third picture still sits on
     * disk, is never shown and is never sent to the model -- present and
     * invisible, which is the worst of both.
     */
    public void deleteScreenshot(String app, String product, String nodeId, int index) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches() || index < 0) {
            return;
        }
        int count = screenshotCount(app, product, nodeId);
        if (index >= count) {
            return;
        }
        screensFolder(app, product).ifPresent(folder -> {
            try {
                for (int i = index; i < count - 1; i++) {
                    Files.move(folder.resolve(shotName(nodeId, i + 1)),
                            folder.resolve(shotName(nodeId, i)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.deleteIfExists(folder.resolve(shotName(nodeId, count - 1)));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Cannot delete screenshot " + nodeId + "#" + index, e);
            }
        });
    }

    /**
     * Index 0 keeps the plain {@code <id>.png} name deliberately: every flow
     * folder written before a screen could hold more than one picture already
     * IS that file, so it becomes picture 1 with nothing to migrate.
     */
    private static String shotName(String nodeId, int index) {
        return index == 0 ? nodeId + ".png" : nodeId + "-" + index + ".png";
    }

    /* ------------------------ what the model read ------------------------ */

    public void writeRead(String app, String product, String nodeId, String yaml) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return;
        }
        screensFolder(app, product).ifPresent(folder -> {
            try {
                Files.createDirectories(folder);
                Files.writeString(folder.resolve(nodeId + ".read.yaml"), yaml, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write read draft " + nodeId, e);
            }
        });
    }

    public Optional<String> read(String app, String product, String nodeId) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return Optional.empty();
        }
        return screensFolder(app, product)
                .map(folder -> folder.resolve(nodeId + ".read.yaml"))
                .filter(Files::isRegularFile)
                .map(FlowStore::text);
    }

    /** Every node's read draft, for the prompt. */
    public Map<String, String> reads(String app, String product, Flow flow) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FlowModel.Node node : flow.nodes()) {
            read(app, product, node.id()).ifPresent(yaml -> out.put(node.id(), yaml));
        }
        return out;
    }

    /* ------------------------------- the plan ------------------------------ */

    public Plan plan(String app, String product) {
        Path file = folder(app, product).map(f -> f.resolve("plan.json")).orElse(null);
        if (file == null || !Files.isRegularFile(file)) {
            return Plan.empty();
        }
        try {
            return mapper.readValue(text(file), Plan.class);
        } catch (RuntimeException unreadable) {
            System.err.println("[flow] " + file + " okunamadı: " + unreadable);
            return Plan.empty();
        }
    }

    public void writePlan(String app, String product, Plan plan) {
        folder(app, product).ifPresent(folder -> {
            try {
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("plan.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write plan for " + app + "/" + product, e);
            }
        });
    }

    /* ------------------------------ helpers ------------------------------ */

    /** {@code products/<app>/<product>/flow}, or empty when the names are not path-safe. */
    private Optional<Path> folder(String app, String product) {
        if (!SAFE_NAME.matcher(app == null ? "" : app).matches()
                || !SAFE_NAME.matcher(product == null ? "" : product).matches()) {
            return Optional.empty();
        }
        Path folder = root.resolve(app.toLowerCase()).resolve(product).resolve("flow").normalize();
        return folder.startsWith(root) ? Optional.of(folder) : Optional.empty();
    }

    private Optional<Path> screensFolder(String app, String product) {
        return folder(app, product).map(f -> f.resolve("screens"));
    }

    /** The bytes of a {@code data:} URL, or null when it is not one. */
    static byte[] decode(String dataUrl) {
        if (dataUrl == null) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        String base64 = comma < 0 ? dataUrl : dataUrl.substring(comma + 1);
        try {
            return Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    static String toDataUrl(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Node ids the UI may not have given yet — stable and filename-safe. */
    public static String newNodeId(List<FlowModel.Node> existing) {
        int next = 1;
        while (true) {
            String id = "e" + next;
            boolean taken = existing.stream().anyMatch(n -> id.equals(n.id()));
            if (!taken) {
                return id;
            }
            next++;
        }
    }
}
