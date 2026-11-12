package orchestrator.aitrace;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * The Token Takibi panel's reads. See docs/TOKEN-TAKIBI.md.
 *
 * {@code period}: 24h, 7d, 30d or all (anything else reads as 7d).
 * {@code kind}: comma separated session kinds; blank lists everything except
 * the model health check.
 */
@RestController
@RequestMapping("/api/v1/ai-traces")
public class AiTraceController {

    private final AiTraceService traces;

    public AiTraceController(AiTraceService traces) {
        this.traces = traces;
    }

    @GetMapping
    public AiTraceService.Overview overview(@RequestParam(defaultValue = "7d") String period,
                                            @RequestParam(required = false) String kind) {
        return traces.overview(period, kind);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        UUID session = uuid(id);
        if (session == null) {
            return notFound(id);
        }
        return traces.detail(session).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> notFound(id));
    }

    @GetMapping("/{id}/turns/{seq}/messages")
    public ResponseEntity<?> messages(@PathVariable String id, @PathVariable int seq) {
        UUID session = uuid(id);
        if (session == null) {
            return notFound(id);
        }
        return traces.messages(session, seq).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "Bu turun kaydı diskte yok: " + seq)));
    }

    /**
     * The same turn as ONE text: every message in the order the model read it,
     * then its answer. Plain text so it can be read top to bottom, copied and
     * diffed against another turn.
     */
    @GetMapping("/{id}/turns/{seq}/transcript")
    public ResponseEntity<?> transcript(@PathVariable String id, @PathVariable int seq) {
        UUID session = uuid(id);
        if (session == null) {
            return notFound(id);
        }
        return traces.transcript(session, seq)
                .<ResponseEntity<?>>map(AiTraceController::plainText)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "Bu turun kaydı diskte yok: " + seq)));
    }

    /** One stored text, whole. Already masked when it was written. */
    @GetMapping("/{id}/content/{sha}")
    public ResponseEntity<?> content(@PathVariable String id, @PathVariable String sha) {
        UUID session = uuid(id);
        if (session == null || !TraceFiles.isSha(sha)) {
            return notFound(id);
        }
        return traces.content(session, sha)
                .<ResponseEntity<?>>map(AiTraceController::plainText)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Metin bulunamadı")));
    }

    /** UTF-8 is stated, or a Turkish page snapshot arrives as mojibake. */
    private static ResponseEntity<?> plainText(String text) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(text);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException notAnId) {
            return null;
        }
    }

    private static ResponseEntity<?> notFound(String id) {
        return ResponseEntity.status(404).body(Map.of("error", "AI oturumu bulunamadı: " + id));
    }
}
