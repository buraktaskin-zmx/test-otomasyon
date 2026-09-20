package orchestrator.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Serves the screen catalogs the LLM prompt is built from.
 *
 * The YAML is handed to the model VERBATIM rather than parsed into objects.
 * Parsing would buy nothing — the model reads YAML perfectly well — and would
 * cost a schema that has to be kept in step with catalogs/SCHEMA.md every time
 * an analyst adds a field type. Fewer moving parts, and a catalog edit needs no
 * Java change.
 */
@Service
public class CatalogService {

    /**
     * Names come from an HTTP request and are used to build a path, so they are
     * restricted to a conservative alphabet rather than sanitised afterwards.
     * The same containment reasoning as ArtifactService.resolve on the executor:
     * a "../.." would otherwise hand out any file the process can read.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Path root;

    public CatalogService(@Value("${catalogs.dir:./catalogs}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** The raw YAML for one flow, or empty when there is no such catalog. */
    public Optional<String> read(String app, String flow) {
        if (!SAFE_NAME.matcher(app == null ? "" : app).matches()
                || !SAFE_NAME.matcher(flow == null ? "" : flow).matches()) {
            return Optional.empty();
        }
        Path file = root.resolve(app.toLowerCase()).resolve(flow + ".yaml").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read catalog " + file, e);
        }
    }

    /** Every catalog as "app/flow", so the UI can offer a list to pick from. */
    public List<String> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .map(root::relativize)
                    .map(p -> p.toString().replace('\\', '/').replaceFirst("\\.yaml$", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list catalogs under " + root, e);
        }
    }

    public Path root() {
        return root;
    }
}
