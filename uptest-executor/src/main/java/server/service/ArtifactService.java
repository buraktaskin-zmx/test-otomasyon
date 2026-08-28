package server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Read-only access to what a run left on disk: the Gherkin that ran, the log,
 * the Cucumber HTML report and the Playwright traces.
 *
 * Everything lives under {featuresFolder}/{runId}/, which is also the volume
 * the orchestrator can see, so results need no extra transfer hop.
 */
@Service
public class ArtifactService {

    /** How deep a run directory goes: traces/ is the only subdirectory. */
    private static final int MAX_DEPTH = 2;

    public record Artifact(String name, long sizeBytes, String contentType) {
    }

    private final Path baseFolder;

    public ArtifactService(@Value("${featuresFolder}") String featuresFolder) {
        this.baseFolder = Path.of(featuresFolder).toAbsolutePath().normalize();
    }

    public List<Artifact> list(UUID runId) throws IOException {
        Path runDir = runDir(runId);
        if (!Files.isDirectory(runDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(runDir, MAX_DEPTH)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(file -> toArtifact(runDir, file))
                    .sorted(Comparator.comparing(Artifact::name))
                    .toList();
        }
    }

    /**
     * Resolves a listed artifact name to a real file.
     *
     * The name arrives from the URL, so it is resolved and then checked to still
     * be inside the run directory: without that, "../../" would hand out any
     * file the process can read. Returns empty rather than throwing, so the
     * controller answers 404 either way and reveals nothing about the layout.
     */
    public Optional<Path> resolve(UUID runId, String relativeName) {
        if (relativeName == null || relativeName.isBlank()) {
            return Optional.empty();
        }
        Path runDir = runDir(runId);
        Path target = runDir.resolve(relativeName).normalize();

        if (!target.startsWith(runDir) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    public static String contentTypeOf(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        // .log and .feature are UTF-8 text; the charset matters because both
        // carry Turkish scenario content.
        return "text/plain;charset=UTF-8";
    }

    private Path runDir(UUID runId) {
        return baseFolder.resolve(runId.toString()).normalize();
    }

    private static Artifact toArtifact(Path runDir, Path file) {
        // Always '/' in the API, whatever the host's separator is.
        String name = runDir.relativize(file).toString().replace('\\', '/');
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        return new Artifact(name, size, contentTypeOf(name));
    }
}
