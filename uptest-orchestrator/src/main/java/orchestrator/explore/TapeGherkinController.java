package orchestrator.explore;

import orchestrator.catalog.CatalogService;
import orchestrator.llm.PromptBuilder;
import orchestrator.llm.SuggestService;
import orchestrator.validation.ValidationError;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Gherkin of a finished walk, written by the model from the walk's tape.
 *
 * <p>A SECOND request, made by the UI once "AI ile çalıştır" has answered, so
 * the analyst reads what the agent saw while the scenario is being written —
 * "Gherkin üretiliyor…" under the answer, then the steps. Folding it into the
 * walk's own request would hold the answer back for another minute of model
 * time and lose both if either failed.
 *
 * <p>Why the model and not only the code renderer: the walk is the agent's
 * path, and the agent takes a different detour every run — a Select2 typed
 * into, a refused submit, a date set through the calendar. Each detour cost the
 * renderer a new rule. Telling the real path from the detours is judgement,
 * which is the model's part; keeping the words exact is the code's part, and
 * stays code: {@link Provenance} refuses any quoted text the walk, the catalog
 * and the analyst's text never contained.
 *
 * <p>Never fails the analyst. No vocabulary, no model, a model error — each
 * falls back to the renderer's draft and says so in the notes, so there is
 * always something to read and to save.
 */
@RestController
@RequestMapping("/api/v1/explore")
@ConditionalOnProperty(name = "uptest.explore.enabled", havingValue = "true")
public class TapeGherkinController {

    /**
     * @param tapeId   the id {@code POST /api/v1/explore} answered with
     * @param app      NGC / NGI, with {@code flow} naming the catalog; both
     *                 optional — a walk reaches screens nobody catalogued
     * @param mainMenu where the scenario starts; the tape's own is used when
     *                 blank
     * @param scenario the scenario as the analyst wrote it
     * @param expected its BEKLENEN lines
     */
    public record Request(String tapeId, String app, String flow, String mainMenu,
                          String scenario, List<String> expected) {
    }

    private static final Pattern STEP = Pattern.compile("^\\s*(Given|When|Then|And|But)\\s+(.*)$");

    private final SuggestService suggest;
    private final PromptBuilder prompts;
    private final VocabularyService vocabularies;
    private final CatalogService catalogs;
    private final Path tapeDir;

    public TapeGherkinController(SuggestService suggest, PromptBuilder prompts,
                                 VocabularyService vocabularies, CatalogService catalogs,
                                 @Value("${uptest.explore.tape-dir:./runs/mcp}") String tapeDir) {
        this.suggest = suggest;
        this.prompts = prompts;
        this.vocabularies = vocabularies;
        this.catalogs = catalogs;
        this.tapeDir = Paths.get(tapeDir);
    }

    @PostMapping("/gherkin")
    public ResponseEntity<?> gherkin(@RequestBody Request body) {
        if (body == null || !TapeFile.isId(body.tapeId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "tapeId is required"));
        }
        Optional<TapeFile.Loaded> loaded = TapeFile.read(tapeDir, body.tapeId());
        if (loaded.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Bu koşunun kaydı bulunamadı: "
                    + body.tapeId()));
        }
        TapeFile.Loaded tape = loaded.get();
        String mainMenu = blank(body.mainMenu()) ? tape.mainMenu() : body.mainMenu();
        List<String> expected = splitExpectations(body.expected());

        TapeRenderer.Rendered draft = TapeRenderer.render(tape.calls(), mainMenu, expected);

        Optional<Vocabulary> vocabulary = vocabularies.get();
        if (vocabulary.isEmpty()) {
            return fallback(draft, "Executor'a ulaşılamadığı için adım listesi bilinmiyor; Gherkin"
                    + " AI ile değil, kural tabanlı taslaktan gösteriliyor.");
        }

        String catalog = (blank(body.app()) || blank(body.flow()))
                ? null
                : catalogs.read(body.app(), body.flow()).orElse(null);
        String scenario = body.scenario() == null ? "" : body.scenario();
        String intent = scenario + (expected.isEmpty() ? "" : "\nBeklenen:\n- " + String.join("\n- ", expected));

        String user = prompts.fromWalk(catalog, scenario, expected,
                TapeEvidence.digest(tape.calls(), mainMenu), draft.steps(), draft.notes());

        SuggestService.Suggestion made;
        try {
            made = suggest.suggestFromWalk(user, intent, vocabulary.get(),
                    SuggestService.ProductContext.NONE,
                    feature -> Provenance.checkAgainstWalk(feature, screenOf(tape), tape.raw(), catalog,
                            intent, mainMenu));
        } catch (RuntimeException failed) {
            Throwable root = failed;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.out.println("[uptest] tape-gherkin: model cagrisi basarisiz — " + root);
            return fallback(draft, "AI ile Gherkin üretilemedi (" + root.getClass().getSimpleName()
                    + ": " + root.getMessage() + "); kural tabanlı taslak gösteriliyor.");
        }

        List<String> steps = stepsOf(made.feature());
        List<String> notes = new ArrayList<>();
        if (!made.valid()) {
            for (ValidationError error : made.validation().errors()) {
                notes.add("Satır " + error.line() + ": " + error.message()
                        + (error.step() == null ? "" : " → " + error.step()));
            }
        }
        System.out.println("[uptest] tape-gherkin: " + body.tapeId() + " — " + steps.size() + " adım, "
                + (made.valid() ? "gecerli" : made.validation().errors().size() + " bulgu")
                + ", " + made.attempts() + " deneme");

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("gherkin", String.join("\n", steps));
        answer.put("notes", notes);
        answer.put("valid", made.valid());
        answer.put("source", "ai");
        answer.put("attempts", made.attempts());
        return ResponseEntity.ok(answer);
    }

    /**
     * What the walk's screens showed: every result, and nothing from the
     * tape's header — the header carries the instruction, which carries the
     * analyst's own sentences, and those must not count as "on screen".
     */
    static String screenOf(TapeFile.Loaded tape) {
        StringBuilder shown = new StringBuilder();
        for (TapeRenderer.Call call : tape.calls()) {
            if (call.result() != null) {
                shown.append(call.result()).append('\n');
            }
        }
        return shown.toString();
    }

    /** " - " between two checks on one BEKLENEN line, the way analysts write them. */
    private static final Pattern JOINED = Pattern.compile("\\s+[-–—]\\s+");

    /**
     * One BEKLENEN line per check.
     *
     * Analysts join checks on one line — "Hızlı Tarih Seçim alanı görünür
     * durumdadır - Sağ tarafta Detaylı Arama bağlantısı görünür durumdadır" —
     * and the model, told "one verify per BEKLENEN line", wrote ONE verify
     * holding both sentences. Splitting here is deterministic; asking the
     * model to split is a request. Only a dash with spaces round it splits:
     * "1.000,00" and "e-Fatura" keep theirs.
     */
    static List<String> splitExpectations(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            for (String part : JOINED.split(line)) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    /** The renderer's draft, with the reason it is what the analyst is reading. */
    private static ResponseEntity<?> fallback(TapeRenderer.Rendered draft, String why) {
        List<String> notes = new ArrayList<>();
        notes.add(why);
        notes.addAll(draft.notes());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("gherkin", String.join("\n", draft.steps()));
        answer.put("notes", notes);
        answer.put("valid", false);
        answer.put("source", "kural");
        answer.put("attempts", 0);
        return ResponseEntity.ok(answer);
    }

    /**
     * The step lines of a feature, keyword and login removed — the shape the
     * UI's proposal already takes, so saving it works exactly as before.
     */
    static List<String> stepsOf(String feature) {
        List<String> steps = new ArrayList<>();
        if (feature == null) {
            return steps;
        }
        for (String line : feature.split("\\R")) {
            Matcher step = STEP.matcher(line);
            if (!step.matches()) {
                continue;
            }
            String text = step.group(2).trim();
            if (text.isEmpty() || text.startsWith("login")) {
                continue;
            }
            steps.add(text);
        }
        return steps;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
