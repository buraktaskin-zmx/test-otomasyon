package orchestrator.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import orchestrator.aitrace.AiTraces;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Veritabanı — asking the test database a question in Turkish.
 *
 * The same model the rest of this application uses, with the database MCP
 * server's tools instead of the browser's. Off unless
 * {@code uptest.db.enabled=true}: like {@code ExploreController}, the
 * endpoints then do not exist and answer 404, which is what the panel reads
 * to decide whether to appear at all.
 *
 * The ChatClient is built from the autoconfigured builder, NOT hand-built
 * from a model — hand-building one cost a day when "AI ile çalıştır" died
 * with {@code DefaultChatOptions cannot be cast to OpenAiChatOptions}.
 */
@RestController
@RequestMapping("/api/v1/db")
@ConditionalOnProperty(name = "uptest.db.enabled", havingValue = "true")
public class DbController {

    /** How many earlier exchanges of the panel's conversation are resent. */
    private static final int HISTORY_LIMIT = 8;

    private final ChatClient client;
    private final DbToolbox toolbox;

    public DbController(ChatClient.Builder builder, DbToolbox toolbox) {
        this.client = builder.build();
        this.toolbox = toolbox;
    }

    /** One earlier line of the panel's conversation. */
    public record Turn(String role, String text) {
    }

    public record Question(String text, List<Turn> history) {
    }

    /**
     * Whether the database server answered, and with which tools.
     *
     * The panel calls this on mount: a 404 means the feature is off, an
     * {@code error} means it is on but the server did not start — and those
     * need opposite things from the analyst, so they are not collapsed into
     * one "unavailable".
     */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<String> names = toolbox.names();
        String failure = toolbox.failure();
        body.put("count", names.size());
        body.put("tools", names);
        if (failure != null) {
            body.put("error", failure);
        }
        return body;
    }

    /** Drops the connection; the next question starts the server again. */
    @PostMapping("/reconnect")
    public Map<String, Object> reconnect() {
        toolbox.reset();
        return tools();
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody Question question) {
        if (question == null || question.text() == null || question.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Soru gerekli"));
        }
        ToolCallback[] callbacks = toolbox.callbacks();
        String failure = toolbox.failure();
        if (failure != null) {
            // 503, not 500: the orchestrator is fine, the database server is
            // not — and the message names which of the two failed.
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Veritabanı aracı başlamadı: " + failure));
        }

        AiTraces.Scope trace = AiTraces.open("db", new AiTraces.Meta().title(title(question.text())));
        try {
            String answer = client.prompt()
                    .system(DbPrompts.SYSTEM)
                    .messages(earlier(question.history()))
                    .user(question.text())
                    .toolCallbacks(callbacks)
                    .call()
                    .content();
            return ResponseEntity.ok(Map.of(
                    "answer", answer == null ? "" : answer,
                    "traceId", trace.id() == null ? "" : trace.id()));
        } catch (RuntimeException cause) {
            // Printed, not swallowed: a 502 whose only record is the UI's
            // toast is an exception nobody can locate (the advisor lesson).
            trace.fail(cause.getMessage());
            System.out.println("[uptest] db/ask basarisiz: " + cause);
            cause.printStackTrace(System.out);
            return ResponseEntity.status(502).body(Map.of("error", String.valueOf(cause.getMessage())));
        } finally {
            trace.close();
        }
    }

    /** The conversation so far, newest-last, capped so an old page is not resent forever. */
    private static List<Message> earlier(List<Turn> history) {
        List<Message> messages = new ArrayList<>();
        if (history == null) {
            return messages;
        }
        List<Turn> tail = history.size() > HISTORY_LIMIT
                ? history.subList(history.size() - HISTORY_LIMIT, history.size())
                : history;
        for (Turn turn : tail) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            messages.add("assistant".equals(turn.role())
                    ? new AssistantMessage(turn.text())
                    : new UserMessage(turn.text()));
        }
        return messages;
    }

    private static String title(String text) {
        String one = text.strip().replaceAll("\s+", " ");
        return one.length() <= 60 ? one : one.substring(0, 60) + "…";
    }
}
