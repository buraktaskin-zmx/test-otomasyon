package server.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import server.service.ArtifactService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves one run's files. Reached only through the orchestrator — analysts
 * never call an executor directly.
 */
@RestController
@RequestMapping("/cucumber/artifacts")
public class ArtifactController {

    private final ArtifactService artifacts;

    public ArtifactController(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping("/{id}")
    public List<ArtifactService.Artifact> list(@PathVariable("id") UUID runId) throws IOException {
        return artifacts.list(runId);
    }

    /**
     * The artifact name travels as a QUERY PARAMETER, not a path segment.
     *
     * Trace files live in a subdirectory (traces/<scenario>.zip), and putting
     * that slash in the path means the caller has to encode it as %2F — which
     * Tomcat rejects with 400 by default. A query parameter carries the slash
     * without any of that. The orchestrator still exposes a friendly
     * .../artifacts/traces/x.zip URL to the browser and translates here.
     *
     * {@link ArtifactService#resolve} is what keeps the name from becoming a
     * path-traversal hole.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable("id") UUID runId,
                                         @RequestParam("name") String name) {
        Optional<Path> file = artifacts.resolve(runId, name);

        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Path path = file.get();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, ArtifactService.contentTypeOf(name));

        if (name.toLowerCase().endsWith(".zip")) {
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + path.getFileName() + "\"");
        }

        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(path));
    }
}
