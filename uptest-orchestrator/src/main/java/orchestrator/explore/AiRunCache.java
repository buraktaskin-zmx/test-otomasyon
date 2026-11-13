package orchestrator.explore;

import orchestrator.explore.ReplayCalls.ReplayCall;
import orchestrator.persistence.AiRunCacheEntity;
import orchestrator.persistence.AiRunCacheRepository;
import orchestrator.product.ProductService;
import orchestrator.product.ScenarioStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The step cache behind "Hızlı çalıştır".
 *
 * <p>Written after an "AI ile çalıştır" walk PASSES: the walk's tool calls,
 * each with a page-independent target ({@link ReplayCalls}). Read by
 * "Hızlı çalıştır", which sends them again ({@link FastReplay}). A call that
 * errors there drops the row: the screen or its data has moved on, and the
 * button is disabled until an agent walk passes again.
 *
 * <p>The key is the saved scenario (its id) plus the text it was run with:
 * edit the scenario and the row no longer counts. Nothing here carries a
 * verdict — every fast run is judged again.
 *
 * <p>Every decision is printed with the {@code [uptest] ai-cache} prefix.
 */
@Service
public class AiRunCache {

    /** What one run did with the cache; also the answer's {@code cache} block. */
    enum State { NONE, WALKED, MISSING, HIT, STOPPED }

    /** One run's dealings with the cache. Filled in as the run goes. */
    static final class Visit {
        final UUID scenarioId;
        final String scenarioPath;
        final String textHash;
        final String cif;
        State state;
        String note;
        List<ReplayCall> calls = List.of();
        List<String> log = List.of();
        int stoppedAt;
        String saved;

        Visit(UUID scenarioId, String scenarioPath, String textHash, String cif, State state, String note) {
            this.scenarioId = scenarioId;
            this.scenarioPath = scenarioPath;
            this.textHash = textHash;
            this.cif = cif;
            this.state = state;
            this.note = note;
        }

        boolean hit() {
            return state == State.HIT;
        }

        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", state.name().toLowerCase(java.util.Locale.ROOT));
            if (note != null) {
                out.put("note", note);
            }
            out.put("stepCount", calls.size());
            if (stoppedAt > 0) {
                out.put("stoppedAt", stoppedAt);
            }
            out.put("log", log);
            if (saved != null) {
                out.put("saved", saved);
            }
            return out;
        }
    }

    /** For the "Hızlı çalıştır" button: is there a cache this scenario, as written now, can use. */
    public record Status(boolean available, int stepCount, Instant savedAt, String reason) {
    }

    private static final String PREFIX = "[uptest] ai-cache ";

    private final AiRunCacheRepository rows;
    private final ProductService products;

    public AiRunCache(AiRunCacheRepository rows, ProductService products) {
        this.rows = rows;
        this.products = products;
    }

    /** "AI ile çalıştır": the cache is not read; the walk may write it. */
    Visit walked(String app, String product, String scenarioPath, String text, String cif) {
        UUID id = scenarioId(app, product, scenarioPath);
        return new Visit(id, scenarioPath, keyOf(text), cif, id == null ? State.NONE : State.WALKED,
                id == null ? "kayıtlı senaryo değil" : null);
    }

    /** "Hızlı çalıştır": the cache this scenario's current text can use, or why there is none. */
    Visit lookup(String app, String product, String scenarioPath, String text, String cif) {
        UUID id = scenarioId(app, product, scenarioPath);
        String hash = keyOf(text);
        Usable usable = usable(id, text);
        Status status = usable.status();
        if (!status.available()) {
            System.out.println(PREFIX + "yok: " + status.reason() + " (" + scenarioPath + ")");
            return new Visit(id, scenarioPath, hash, cif, State.MISSING, status.reason());
        }
        AiRunCacheEntity row = safeFind(id).orElseThrow();
        Visit visit = new Visit(id, scenarioPath, hash, cif, State.HIT,
                status.stepCount() + " çağrı, kaynak bant " + row.getSourceTapeId());
        visit.calls = usable.calls();
        System.out.println(PREFIX + "HIT: " + visit.calls.size() + " çağrı (bant " + row.getSourceTapeId() + ", "
                + row.getSuccessCount() + " başarılı tekrar)");
        usable.renamed().forEach(rename -> System.out.println(PREFIX + "   değer güncel metinden: " + rename));
        return visit;
    }

    public Status status(String app, String product, String scenarioPath, String text) {
        return usable(scenarioId(app, product, scenarioPath), text).status();
    }

    /** What a fast run of {@code text} would send, or why it cannot run. */
    private record Usable(Status status, List<ReplayCall> calls, List<String> renamed) {
        static Usable no(String reason) {
            return new Usable(new Status(false, 0, null, reason), List.of(), List.of());
        }
    }

    /**
     * The row's calls adapted to the scenario as written NOW (ScenarioShape):
     * its changed quoted values put in, its Beklenen re-attached from the
     * current text. A row written before 13.11.2026 has no saved text and is
     * held to the whole-text hash it was stored under.
     */
    private Usable usable(UUID id, String text) {
        if (id == null) {
            return Usable.no("kayıtlı bir senaryo değil");
        }
        Optional<AiRunCacheEntity> row = safeFind(id);
        if (row.isEmpty()) {
            return Usable.no("kayıtlı akış yok; önce 'AI ile çalıştır' ile bir kez geçmeli");
        }
        List<ReplayCall> calls = ReplayCalls.fromJson(row.get().getStepsJson());
        if (calls.isEmpty()) {
            return Usable.no("kayıtlı akış eski biçimde; 'AI ile çalıştır' ile yeniden kaydedin");
        }
        String saved = row.get().getScenarioText();
        List<String> renamedLines = List.of();
        if (saved == null) {
            if (!sha256(text).equals(row.get().getTextHash())) {
                return Usable.no("senaryo kayıttan sonra değişmiş; 'AI ile çalıştır' ile yeniden kaydedin");
            }
        } else {
            if (!keyOf(text).equals(keyOf(saved))) {
                return Usable.no("adımlar kayıttan sonra değişmiş (başlık, ön koşul ve Beklenen değişikliği akışı"
                        + " bozmaz; adımdaki tırnaklı bir değer de bozmaz); 'AI ile çalıştır' ile yeniden kaydedin");
            }
            Map<String, String> renames = ScenarioShape.renames(saved, text);
            ScenarioShape.Renamed renamed = ScenarioShape.rename(calls, renames);
            if (!renamed.unplaced().isEmpty()) {
                return Usable.no("değişen değer kayıtlı akışta bir hedefe karşılık gelmiyor ("
                        + String.join(", ", renamed.unplaced()) + "); eski tıklama yeni değeri yapamaz."
                        + " 'AI ile çalıştır' ile yeniden kaydedin");
            }
            calls = renamed.calls();
            renamedLines = renames.entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\" → \"" + e.getValue() + "\"").toList();
        }
        calls = ScenarioShape.realign(calls, text);
        return new Usable(new Status(true, calls.size(), row.get().getCreatedAt(), null), calls, renamedLines);
    }

    /** The key a row is stored under: the steps' shape, or the whole text when it has no numbered steps. */
    static String keyOf(String text) {
        String shape = ScenarioShape.shape(text);
        return sha256(shape != null ? shape : text);
    }

    /** A fast run hit a call that no longer works: the row goes, the button is disabled. */
    void invalidate(Visit visit, String reason) {
        if (visit == null || visit.scenarioId == null) {
            return;
        }
        try {
            rows.deleteById(visit.scenarioId);
            visit.state = State.STOPPED;
            visit.note = reason;
            visit.saved = "kayıtlı akış silindi";
            System.out.println(PREFIX + "silindi: " + reason + " (" + visit.scenarioPath + ")");
        } catch (RuntimeException failed) {
            System.out.println(PREFIX + "silinemedi — " + failed.getClass().getSimpleName() + ": "
                    + failed.getMessage());
        }
    }

    /**
     * After the verdict. A fast run is counted; an agent walk that PASSED is
     * written down. A failed walk is never saved: replaying a wrong path is the
     * bug promoted to a passing test this project keeps warning about.
     */
    void settle(Visit visit, RunVerdict verdict, ActionTape tape, String scenarioText) {
        if (visit == null || visit.scenarioId == null || visit.state == State.STOPPED) {
            return;
        }
        RunVerdict.Result result = verdict == null ? null : verdict.result();
        boolean passed = result != null && result.passed();
        try {
            if (visit.hit()) {
                safeFind(visit.scenarioId).ifPresent(row -> {
                    row.used(Instant.now());
                    if (passed) {
                        row.passed(Instant.now());
                    } else {
                        row.failed();
                    }
                    rows.save(row);
                });
                System.out.println(PREFIX + "hızlı koşu " + (passed ? "GEÇTİ" : "GEÇMEDİ (beklenen)")
                        + "; akış yerinde kalıyor");
                return;
            }
            if (visit.state != State.WALKED) {
                return;
            }
            if (!passed) {
                System.out.println(PREFIX + "kaydedilmedi: koşu geçmedi");
                visit.saved = "kaydedilmedi: koşu geçmedi";
                return;
            }
            ReplayCalls.Extracted extracted = ReplayCalls.fromWalk(tape.calls(), LocalDate.now());
            if (!extracted.usable()) {
                System.out.println(PREFIX + "kaydedilmedi: " + extracted.refusal());
                visit.saved = "kaydedilmedi: " + extracted.refusal();
                return;
            }
            List<ReplayCall> calls = new ArrayList<>(extracted.calls());
            List<String> unmatched = ReplayCalls.align(calls, scenarioText);
            AiRunCacheEntity row = safeFind(visit.scenarioId).orElseGet(() -> new AiRunCacheEntity(visit.scenarioId));
            row.store(visit.scenarioPath, visit.textHash, scenarioText, ReplayCalls.toJson(calls), calls.size(),
                    tape.id(), visit.cif, Instant.now());
            rows.save(row);
            visit.calls = calls;
            visit.saved = calls.size() + " çağrı kaydedildi";
            System.out.println(PREFIX + "kaydedildi: " + calls.size() + " çağrı (bant " + tape.id() + ")");
            calls.forEach(call -> System.out.println(PREFIX + "   " + call.says()
                    + (call.target() == null ? "" : "  [" + call.target() + "]")
                    + (call.expected() == null ? "" : "  ⟶ beklenen: " + call.expected())));
            unmatched.forEach(line -> System.out.println(PREFIX + "   adımı bulunamayan beklenen (son ekranda"
                    + " değerlendirilecek): " + line));
        } catch (RuntimeException failed) {
            // A cache that cannot be written must not cost the run its answer.
            System.out.println(PREFIX + "yazılamadı — " + failed.getClass().getSimpleName() + ": "
                    + failed.getMessage());
            visit.saved = "yazılamadı: " + failed.getClass().getSimpleName();
        }
    }

    private Optional<AiRunCacheEntity> safeFind(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return rows.findById(id);
        } catch (RuntimeException unreadable) {
            System.out.println(PREFIX + "okunamadı — " + unreadable.getClass().getSimpleName() + ": "
                    + unreadable.getMessage());
            return Optional.empty();
        }
    }

    private UUID scenarioId(String app, String product, String path) {
        if (blank(app) || blank(product) || blank(path)) {
            return null;
        }
        try {
            return products.scenario(app, product, path).map(ScenarioStore.Stored::id).orElse(null);
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text.strip()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
