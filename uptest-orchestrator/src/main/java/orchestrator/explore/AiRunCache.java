package orchestrator.explore;

import orchestrator.explore.CachedSteps.CachedStep;
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
 * "AI ile çalıştır"'s step cache: looked up before the agent starts, written
 * after a walk PASSES.
 *
 * <p>The key is the saved scenario (its id) plus the text it was run with.
 * Edit the scenario and the text no longer matches; the cache is ignored
 * and the next passed walk replaces it. The cache never carries a verdict —
 * a cached run is judged again every time ({@link CachedWalk}).
 *
 * <p>Every decision is printed with the {@code [uptest] ai-cache} prefix:
 * hit or miss and why, saved or not and why.
 */
@Service
public class AiRunCache {

    /** What happened to the cache on one run; also the answer's {@code cache} block. */
    enum State { NONE, MISS, IGNORED, HIT, TAKEOVER }

    /** One run's dealings with the cache. Filled in as the run goes. */
    static final class Visit {
        final UUID scenarioId;
        final String scenarioPath;
        final String textHash;
        final String cif;
        State state;
        String note;
        List<CachedStep> steps = List.of();
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
            out.put("stepCount", steps.size());
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

    private static final String PREFIX = "[uptest] ai-cache ";

    private final AiRunCacheRepository rows;
    private final ProductService products;

    public AiRunCache(AiRunCacheRepository rows, ProductService products) {
        this.rows = rows;
        this.products = products;
    }

    /**
     * @param ignore the analyst asked for a fresh walk ("Cache'siz, ajanla baştan")
     */
    Visit lookup(String app, String product, String scenarioPath, String text, String cif, boolean ignore) {
        UUID id = scenarioId(app, product, scenarioPath);
        String hash = sha256(text);
        if (id == null) {
            System.out.println(PREFIX + "yok: kayıtlı bir senaryodan gelmeyen koşu cache kullanmaz");
            return new Visit(null, scenarioPath, hash, cif, State.NONE, "kayıtlı senaryo değil");
        }
        if (ignore) {
            System.out.println(PREFIX + "atlandı: analist ajanla baştan istedi (" + scenarioPath + ")");
            return new Visit(id, scenarioPath, hash, cif, State.IGNORED, "analist cache'siz istedi");
        }
        Optional<AiRunCacheEntity> row = safeFind(id);
        if (row.isEmpty()) {
            System.out.println(PREFIX + "MISS: bu senaryonun kayıtlı akışı yok (" + scenarioPath + ")");
            return new Visit(id, scenarioPath, hash, cif, State.MISS, "kayıtlı akış yok");
        }
        if (!hash.equals(row.get().getTextHash())) {
            System.out.println(PREFIX + "MISS: senaryo metni kayıttan sonra değişmiş (" + scenarioPath + ")");
            return new Visit(id, scenarioPath, hash, cif, State.MISS, "senaryo metni değişmiş");
        }
        List<CachedStep> steps = CachedSteps.fromJson(row.get().getStepsJson());
        if (steps.isEmpty()) {
            System.out.println(PREFIX + "MISS: kayıtlı akış okunamadı (" + scenarioPath + ")");
            return new Visit(id, scenarioPath, hash, cif, State.MISS, "kayıtlı akış okunamadı");
        }
        Visit visit = new Visit(id, scenarioPath, hash, cif, State.HIT,
                steps.size() + " adım, kaynak bant " + row.get().getSourceTapeId());
        visit.steps = steps;
        System.out.println(PREFIX + "HIT: " + steps.size() + " adım (bant " + row.get().getSourceTapeId()
                + ", " + row.get().getSuccessCount() + " başarılı / " + row.get().getFailureCount()
                + " başarısız tekrar)");
        steps.forEach(step -> System.out.println(PREFIX + "   " + CachedSteps.describe(step)));
        return visit;
    }

    /**
     * After the verdict: count a cached run, or save the steps of a walk the
     * agent took part in. Only a PASSED walk is saved — a failed one may have
     * gone the wrong way, and replaying a wrong way is the bug promoted to a
     * passing test this project keeps warning about.
     */
    void settle(Visit visit, RunVerdict verdict, ActionTape tape, StepJournal journal, String scenarioText) {
        if (visit == null || visit.scenarioId == null) {
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
                System.out.println(PREFIX + "kayıtlı akışla koşu " + (passed ? "GEÇTİ" : "GEÇMEDİ")
                        + "; akış yerinde kalıyor");
                return;
            }
            if (visit.state == State.TAKEOVER && !passed) {
                safeFind(visit.scenarioId).ifPresent(row -> {
                    row.used(Instant.now());
                    row.failed();
                    rows.save(row);
                });
            }
            if (!passed) {
                System.out.println(PREFIX + "kaydedilmedi: koşu geçmedi");
                visit.saved = "kaydedilmedi: koşu geçmedi";
                return;
            }
            CachedSteps.Extracted extracted = CachedSteps.fromWalk(tape.calls(), journal, LocalDate.now());
            if (!extracted.usable()) {
                System.out.println(PREFIX + "kaydedilmedi: " + extracted.refusal());
                visit.saved = "kaydedilmedi: " + extracted.refusal();
                return;
            }
            List<CachedStep> steps = new ArrayList<>(extracted.steps());
            List<String> unmatched = CachedSteps.align(steps, scenarioText);
            AiRunCacheEntity row = safeFind(visit.scenarioId).orElseGet(() -> new AiRunCacheEntity(visit.scenarioId));
            row.store(visit.scenarioPath, visit.textHash, CachedSteps.toJson(steps), steps.size(), tape.id(),
                    visit.cif, Instant.now());
            rows.save(row);
            visit.saved = steps.size() + " adım kaydedildi";
            System.out.println(PREFIX + "kaydedildi: " + steps.size() + " adım (bant " + tape.id() + ")");
            steps.forEach(step -> System.out.println(PREFIX + "   " + CachedSteps.describe(step)
                    + (step.expected() == null ? "" : "  ⟶ beklenen: " + step.expected())));
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
