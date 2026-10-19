package orchestrator.chat;

import orchestrator.catalog.CatalogService;
import orchestrator.llm.LoginStep;
import orchestrator.llm.SuggestService;
import orchestrator.product.ProductService;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writing a scenario by talking about it.
 *
 * No browser. This is the generator with the repair turn pointed at a PERSON:
 * generate once, and when the validator objects, ask the analyst instead of
 * letting the model guess. Every turn costs exactly what {@code /suggest}
 * costs — one model call, seconds — because it IS /suggest, with
 * {@link SuggestService#suggestOnce} in place of the repair loop.
 *
 * The questions come from {@link ChatQuestions}, which derives them from the
 * validator's own findings. Nothing here invents a question, and nothing here
 * decides what a scenario should say: the analyst does, one answer at a time,
 * and the result goes through the same validator and the same run path as a
 * scenario typed by hand.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    /** What the browser is handed after every turn. */
    public record ChatTurn(
            String sessionId,
            List<ChatSession.Message> messages,
            /** The question waiting for an answer, or null when there is none. */
            ChatQuestions.ChatQuestion question,
            /**
             * The request as it now stands: the opening text plus every
             * answer, in the words the model was given them in.
             *
             * Sent because the browser needs it, not for display. A draft
             * handed to the editor is RE-VALIDATED there, and
             * checkAssertionsAreEarned looks for an expected value in the
             * intent — so a value the analyst supplied mid-conversation has
             * to travel with the scenario. Sending only the opening line
             * would make the editor raise the very question the chat had
             * just answered.
             */
            String request,
            /**
             * The product this conversation is being held under, or null.
             *
             * Travels for exactly the reason {@code request} does. The editor
             * re-validates whatever the chat hands it, and the product decides
             * which asserted sentences count as catalogued — so a draft that
             * legitimately asserts a product's error message would be flagged
             * the moment it left the chat if this did not go with it.
             */
            String product,
            /** The draft as it stands; null before the first generation. */
            String feature,
            ValidationResult validation,
            /** Valid and nothing pending: ready to run or to hand to the editor. */
            boolean complete
    ) {
    }

    public record StartRequest(String app, String flow, String cif, String userid, String text,
                               String product, List<String> ruleIds) {
    }

    /**
     * An answer. Either a button ({@code optionId}, plus {@code value} when
     * that button asked for one) or something typed ({@code text}).
     */
    public record ReplyRequest(String optionId, String value, String text) {
    }

    private final ChatSessionStore sessions;
    private final CatalogService catalogs;
    private final ProductService products;
    private final VocabularyService vocabularyService;
    private final SuggestService suggestService;

    public ChatController(ChatSessionStore sessions, CatalogService catalogs, ProductService products,
                          VocabularyService vocabularyService, SuggestService suggestService) {
        this.sessions = sessions;
        this.catalogs = catalogs;
        this.products = products;
        this.vocabularyService = vocabularyService;
        this.suggestService = suggestService;
    }

    /**
     * Re-resolved from disk on every turn rather than held on the session.
     *
     * A conversation lasts minutes and the rules file is a repository file
     * someone may be editing; reading it each turn means an edit takes effect
     * on the next answer instead of requiring a new conversation. The cost is
     * two small file reads against a model call that takes seconds.
     */
    private SuggestService.ProductContext productContext(ChatSession session) {
        return products.load(session.app(), session.product())
                .map(model -> new SuggestService.ProductContext(model,
                        model.selected(session.ruleIds())))
                .orElse(SuggestService.ProductContext.NONE);
    }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody StartRequest body) {
        if (body == null || blank(body.app()) || blank(body.flow())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "app ve flow gerekli",
                    "available", catalogs.list()));
        }
        if (blank(body.text())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ne test edileceğini yazın"));
        }
        // Same rule as the generator panel: a cif without the userid its
        // application needs cannot become a login line, and guessing one is
        // what the check exists to stop.
        if (!blank(body.cif()) && LoginStep.needsUserid(body.app()) && blank(body.userid())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", body.app() + " için kullanıcı ID zorunlu"));
        }

        ChatSession session = sessions.open(body.app(), body.flow(), body.cif(), body.userid(),
                body.product(), body.ruleIds());
        session.say("you", body.text());
        session.add(body.text());
        return advance(session);
    }

    @PostMapping("/{id}/reply")
    public ResponseEntity<?> reply(@PathVariable String id, @RequestBody ReplyRequest body) {
        Optional<ChatSession> found = sessions.find(id);
        if (found.isEmpty()) {
            return gone();
        }
        ChatSession session = found.get();
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Bir seçenek seçin ya da bir şey yazın"));
        }

        // A button first, because a button that asked for a value arrives with
        // both fields filled and the button is the one that says what the
        // value MEANS.
        String added = ChatQuestions.applied(session.pending(), body.optionId(), body.value());
        String spoken = added != null
                ? labelOf(session, body.optionId(), body.value())
                : body.text();

        if (added == null) {
            if (blank(body.text())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Bir seçenek seçin ya da bir şey yazın"));
            }
            added = body.text();
        }

        session.say("you", spoken);
        session.add(added);
        return advance(session);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return sessions.find(id)
                .<ResponseEntity<?>>map(session -> ResponseEntity.ok(turn(session)))
                .orElseGet(this::gone);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> end(@PathVariable String id) {
        sessions.close(id);
        return ResponseEntity.ok(Map.of("closed", String.valueOf(id)));
    }

    /**
     * One model call, one verdict, and whatever question the verdict raises.
     *
     * The model is a remote service, so a refusal is an operational state
     * rather than a bug: 502 with the root cause, the same shape
     * ScenarioController uses, instead of a stack trace the analyst cannot
     * read.
     */
    private ResponseEntity<?> advance(ChatSession session) {
        Optional<Vocabulary> vocabulary = vocabularyService.get();
        if (vocabulary.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Hiçbir executor'a ulaşılamıyor, adım sözlüğü bilinmiyor"));
        }
        Optional<String> catalog = catalogs.read(session.app(), session.flow());
        if (catalog.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Katalog yok: " + session.app() + "/" + session.flow(),
                    "available", catalogs.list()));
        }

        SuggestService.Suggestion suggestion;
        try {
            suggestion = suggestService.suggestOnce(
                    catalog.get(),
                    session.request(),
                    vocabulary.get(),
                    LoginStep.forApp(session.app(), session.cif(), session.userid()),
                    productContext(session));
        } catch (Exception failed) {
            Throwable root = failed;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", root.getClass().getSimpleName(),
                    "detail", String.valueOf(root.getMessage())));
        }

        Optional<ChatQuestions.Resolution> pending = ChatQuestions.resolve(suggestion.validation());
        session.record(suggestion.feature(), suggestion.validation(), pending.orElse(null));

        if (pending.isPresent()) {
            session.ask(pending.get().question().text());
        } else {
            session.ask("Senaryo hazır — " + suggestion.validation().stepCount()
                    + " adımın tamamı geçerli. Çalıştırabilir ya da editöre aktarabilirsiniz.");
        }
        return ResponseEntity.ok(turn(session));
    }

    private static ChatTurn turn(ChatSession session) {
        ChatQuestions.Resolution pending = session.pending();
        return new ChatTurn(
                session.id(),
                session.messages(),
                pending == null ? null : pending.question(),
                session.request(),
                session.product(),
                session.feature(),
                session.validation(),
                pending == null && session.validation() != null && session.validation().valid());
    }

    /**
     * What to show as the analyst's own line when they pressed a button.
     *
     * The button's label, not the sentence it adds to the request: the
     * transcript should read back as the conversation that happened
     * ("Doğrulamayı atla"), not as the instruction it was translated into.
     */
    private static String labelOf(ChatSession session, String optionId, String value) {
        ChatQuestions.Resolution pending = session.pending();
        if (pending == null) {
            return value;
        }
        String label = pending.question().options().stream()
                .filter(option -> option.id().equals(optionId))
                .map(ChatQuestions.ChatOption::label)
                .findFirst()
                .orElse(optionId);
        return blank(value) ? label : label + ": " + value.trim();
    }

    private ResponseEntity<?> gone() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Bu sohbet artık yok. Yeni bir sohbet başlatın."));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
