package orchestrator.flow;

import orchestrator.flow.FlowModel.Flow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The drawn flow: read it, save it, attach screenshots, have the screens read.
 *
 * <p>The vision model is optional. {@link ScreenReader} only exists when
 * {@code uptest.vision.enabled} is true, so it is injected through an
 * {@link ObjectProvider} and only the read endpoint refuses — drawing the
 * tree, storing screenshots and everything downstream of a hand-written
 * catalog keep working on an install with no vision endpoint.
 */
@RestController
@RequestMapping("/api/v1/products/{app}/{product}/flow")
public class FlowController {

    private final FlowStore store;
    private final ObjectProvider<ScreenReader> readers;

    public FlowController(FlowStore store, ObjectProvider<ScreenReader> readers) {
        this.store = store;
        this.readers = readers;
    }

    /** The drawing, which nodes carry a screenshot, what was read, and the plan. */
    @GetMapping
    public Map<String, Object> flow(@PathVariable String app, @PathVariable String product) {
        Flow flow = store.read(app, product);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flow", flow);
        body.put("screenshots", store.screenshotPresence(app, product, flow));
        body.put("reads", store.reads(app, product, flow));
        body.put("plan", store.plan(app, product));
        body.put("visionReady", readers.getIfAvailable() != null);
        return body;
    }

    @PutMapping
    public ResponseEntity<?> save(@PathVariable String app, @PathVariable String product,
                                  @RequestBody Flow flow) {
        String refused = store.write(app, product, flow == null ? Flow.empty() : flow);
        return refused == null
                ? ResponseEntity.ok(Map.of("saved", true))
                : ResponseEntity.badRequest().body(Map.of("error", refused));
    }

    /** {@code dataUrl}: {@code data:image/png;base64,…}, as the clipboard gives it. */
    public record ScreenshotBody(String dataUrl) {
    }

    @PutMapping("/screens/{nodeId}")
    public ResponseEntity<?> putScreenshot(@PathVariable String app, @PathVariable String product,
                                           @PathVariable String nodeId,
                                           @RequestBody ScreenshotBody body) {
        String refused = store.writeScreenshot(app, product, nodeId,
                body == null ? null : body.dataUrl());
        return refused == null
                ? ResponseEntity.ok(Map.of("saved", true))
                : ResponseEntity.badRequest().body(Map.of("error", refused));
    }

    /**
     * The image itself, as bytes.
     *
     * Served rather than embedded in the flow JSON: four screenshots would be
     * megabytes of base64 on every panel open, re-parsed each time, where an
     * {@code <img src>} is cached by the browser and fetched only for the
     * nodes actually on screen.
     */
    @GetMapping("/screens/{nodeId}")
    public ResponseEntity<byte[]> getScreenshot(@PathVariable String app, @PathVariable String product,
                                                @PathVariable String nodeId) {
        return store.screenshot(app, product, nodeId)
                .map(png -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        // Short and private: the analyst replaces a screenshot
                        // and expects to see the new one, not yesterday's.
                        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                        .body(png))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/screens/{nodeId}")
    public ResponseEntity<?> deleteScreenshot(@PathVariable String app, @PathVariable String product,
                                              @PathVariable String nodeId) {
        store.deleteScreenshot(app, product, nodeId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /**
     * Stage 1: the vision model reads every screen that has a screenshot.
     *
     * Answers per node, including the ones that failed, so one unreadable
     * screenshot does not hide the three that worked.
     */
    @PostMapping("/read")
    public ResponseEntity<?> read(@PathVariable String app, @PathVariable String product) {
        ScreenReader reader = readers.getIfAvailable();
        if (reader == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Görsel model kapalı",
                    "detail", "uptest.vision.enabled=true ve uptest.vision.* ayarları gerekli."));
        }
        Flow flow = store.read(app, product);
        List<ScreenReader.Read> reads = reader.readAll(app, product, flow);
        if (reads.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Okunacak ekran yok — önce kutulara ekran görüntüsü ekleyin."));
        }
        return ResponseEntity.ok(Map.of("screens", reads));
    }
}
