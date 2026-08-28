package server.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import server.service.CucumberRunnerResult;
import server.service.CucumberService;
import server.service.ResultStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/cucumber")
public class CucumberController {

    private final CucumberService cucumberService;

    public CucumberController(CucumberService cucumberService) {
        this.cucumberService = cucumberService;
    }

    @GetMapping("/test")
    public String test() {
        return "Hello World";
    }

    /**
     * The step vocabulary this executor actually implements, generated from the
     * @Given/@When/@Then annotations at build time.
     *
     * Served over HTTP rather than copied between modules at build time: the
     * orchestrator already talks to executors, and fetching it this way means
     * the validator can never disagree with the code that is really deployed.
     */
    @GetMapping(value = "/vocabulary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> vocabulary() throws IOException {
        ClassPathResource resource = new ClassPathResource("vocabulary.json");
        if (!resource.exists()) {
            // Only happens if the build skipped the extractor; say so instead of
            // letting the orchestrator validate against an empty vocabulary.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("{\"error\":\"vocabulary.json missing from executor jar\"}");
        }
        try (InputStream in = resource.getInputStream()) {
            return ResponseEntity.ok(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/nodeStatus")
    public Map<UUID, ResultStatus> nodeStatus() {
        return cucumberService.getPoolStatus();
    }

    /**
     * Asynchronous by contract: returns 202 immediately and progress is read
     * from /status. A synchronous endpoint would hold the connection open for
     * minutes and die to proxy timeouts.
     *
     * Returns 409 (via StatusCodeException) when this executor is already busy
     * or the run id was used before, so the orchestrator can dispatch elsewhere
     * instead of having the run silently queued behind a running test.
     */
    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String start(@RequestBody CucumberStartRequest request) {
        cucumberService.start(request.getId(), request.getFeature());
        return "OK";
    }

    @PutMapping("/stop/{id}")
    public String stop(@PathVariable(name = "id") UUID uuid) {
        cucumberService.stop(uuid);
        return "OK";
    }

    @GetMapping("/status/{id}")
    public CucumberRunnerResult status(@PathVariable(name = "id") UUID uuid) throws Exception {
        return cucumberService.getStatus(uuid);
    }
}
