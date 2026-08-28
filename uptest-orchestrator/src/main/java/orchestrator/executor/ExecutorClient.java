package orchestrator.executor;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for one executor. The orchestrator never touches an executor's
 * filesystem or process — only this API.
 */
public class ExecutorClient {

    /** Outcome of asking one executor to take a run. */
    public enum Dispatch {
        ACCEPTED,
        /** 409: busy or the id was already used — try the next executor. */
        REJECTED,
        /** Connection refused, timeout, 5xx — the node may be restarting. */
        UNREACHABLE
    }

    private final String name;
    private final RestClient http;

    public ExecutorClient(String name, String baseUrl, Duration timeout) {
        this.name = name;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // A restarting or hung executor must not stall the orchestrator: every
        // call is bounded, and an unreachable node just loses its turn.
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public String name() {
        return name;
    }

    public Dispatch start(UUID runId, String feature) {
        try {
            HttpStatusCode status = http.post()
                    .uri("/cucumber/start")
                    .body(Map.of("id", runId.toString(), "feature", feature))
                    .retrieve()
                    .onStatus(code -> true, (request, response) -> {
                        // Never throw on status: 409 is a normal answer here.
                    })
                    .toBodilessEntity()
                    .getStatusCode();

            if (status.is2xxSuccessful()) {
                return Dispatch.ACCEPTED;
            }
            if (status.value() == 409) {
                return Dispatch.REJECTED;
            }
            return Dispatch.UNREACHABLE;
        } catch (RestClientException e) {
            return Dispatch.UNREACHABLE;
        }
    }

    /** The executor's own view of a run, or null if it cannot be reached. */
    public Map<String, Object> status(UUID runId) {
        try {
            return http.get()
                    .uri("/cucumber/status/{id}", runId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            return null;
        }
    }

    public boolean stop(UUID runId) {
        try {
            http.put().uri("/cucumber/stop/{id}", runId).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    /** What this run left on disk, or null if the executor cannot be reached. */
    public List<Map<String, Object>> artifacts(UUID runId) {
        try {
            return http.get()
                    .uri("/cucumber/artifacts/{id}", runId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
        } catch (RestClientException e) {
            return null;
        }
    }

    /**
     * One artifact's bytes plus the content type the executor gave it.
     * Returned whole rather than streamed: the largest of these is a trace zip,
     * a few MB, and buffering keeps the executor connection short.
     */
    public ResponseEntity<byte[]> artifact(UUID runId, String name) {
        try {
            return http.get()
                    // Query parameter, not a path segment: a trace name contains
                    // a '/', and encoding that into the path yields %2F, which
                    // Tomcat rejects with 400 before the handler ever runs.
                    .uri(builder -> builder
                            .path("/cucumber/artifacts/{id}/file")
                            .queryParam("name", name)
                            .build(runId))
                    .retrieve()
                    .onStatus(code -> true, (request, response) -> {
                        // 404 is a normal answer for an artifact that never existed.
                    })
                    .toEntity(byte[].class);
        } catch (RestClientException e) {
            return null;
        }
    }

    /** The raw vocabulary.json this executor was built with, or null. */
    public String vocabularyJson() {
        try {
            return http.get().uri("/cucumber/vocabulary").retrieve().body(String.class);
        } catch (RestClientException e) {
            return null;
        }
    }

    public boolean isReachable() {
        try {
            http.get().uri("/cucumber/test").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }
}
