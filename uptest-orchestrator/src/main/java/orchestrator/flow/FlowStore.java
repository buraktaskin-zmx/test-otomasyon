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
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return "Geçersiz ekran kimliği: " + nodeId;
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
            Files.write(folder.get().resolve(nodeId + ".png"), png);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write screenshot " + nodeId, e);
        }
    }

    public Optional<byte[]> screenshot(String app, String product, String nodeId) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return Optional.empty();
        }
        return screensFolder(app, product)
                .map(folder -> folder.resolve(nodeId + ".png"))
                .filter(Files::isRegularFile)
                .map(file -> {
                    try {
                        return Files.readAllBytes(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read " + file, e);
                    }
                });
    }

    /** Which nodes have a screenshot, so the UI can show it without sending megabytes. */
    public Map<String, Boolean> screenshotPresence(String app, String product, Flow flow) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Optional<Path> folder = screensFolder(app, product);
        for (FlowModel.Node node : flow.nodes()) {
            out.put(node.id(), folder
                    .map(f -> Files.isRegularFile(f.resolve(node.id() + ".png")))
                    .orElse(false));
        }
        return out;
    }

    public void deleteScreenshot(String app, String product, String nodeId) {
        if (!SAFE_NAME.matcher(nodeId == null ? "" : nodeId).matches()) {
            return;
        }
        screensFolder(app, product).ifPresent(folder -> {
            try {
                Files.deleteIfExists(folder.resolve(nodeId + ".png"));
                Files.deleteIfExists(folder.resolve(nodeId + ".read.yaml"));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot delete screenshot " + nodeId, e);
            }
        });
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
