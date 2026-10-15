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

    /**
     * Writes a catalog, creating the application's folder if it is new.
     *
     * The agent's draft was previously copied out of a textarea by hand before
     * anything could use it, and a draft nobody saves is a draft nobody runs.
     * Saving it here puts it exactly where {@link #list} looks, so it appears
     * in the generator's catalog list on the next load with no other step.
     *
     * Overwrites on purpose: a second, better walk of the same screen is a
     * correction, not a new flow. What protects the file is that the analyst
     * chooses the name and sees the draft before pressing save.
     *
     * UTF-8 explicitly, like every other file this project writes — the
     * platform default corrupts "Fatura Dönemi" on a differently configured
     * host, and a catalog is nothing but Turkish labels.
     *
     * @return the "app/flow" name it was stored under, or empty when the name
     *         is not one this service will build a path from
     */
    public Optional<String> write(String app, String flow, String yaml) {
        if (!SAFE_NAME.matcher(app == null ? "" : app).matches()
                || !SAFE_NAME.matcher(flow == null ? "" : flow).matches()
                || yaml == null || yaml.isBlank()) {
            return Optional.empty();
        }
        Path folder = root.resolve(app.toLowerCase()).normalize();
        Path file = folder.resolve(flow + ".yaml").normalize();
        if (!file.startsWith(root)) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(folder);
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write catalog " + file, e);
        }
        return Optional.of(app.toLowerCase() + "/" + flow);
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
