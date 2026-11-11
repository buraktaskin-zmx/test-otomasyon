package orchestrator.aitrace;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Counts a text's tokens with the model server's own tokenizer.
 *
 * <p>The server reports ONE prompt figure per call. Splitting it — how much
 * of a turn was the system prompt, the tool definitions, the page snapshots —
 * needs each part counted on its own, and the only count that is not a guess
 * is the one made with the tokenizer the model actually uses. vLLM exposes it
 * at {@code POST /tokenize}, beside (not under) {@code /v1}.
 *
 * <p><b>Whether that route is reachable is not known from here.</b> The
 * OpenShift route may publish only {@code /v1}. So a failure is an expected
 * answer, not an error: it is logged once, the counter backs off for ten
 * minutes rather than adding a failed request to every message of every turn,
 * and the caller falls back to an estimate it marks as one.
 *
 * <p>Never called on a request thread: {@link AiTraceRecorder} runs it on its
 * own worker after the turn is recorded, so a slow tokenizer delays a number
 * on a dashboard, never a model call.
 */
final class TokenCounter {

    private static final Duration BACK_OFF = Duration.ofMinutes(10);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final String url;

    private volatile Instant downUntil;
    private volatile Boolean lastWorked;

    /**
     * @param url   the server root; null or blank disables counting entirely
     * @param model sent with each request, as vLLM's tokenize endpoint wants it
     */
    TokenCounter(String url, String model, ObjectMapper mapper) {
        this.mapper = mapper;
        this.model = model;
        this.url = url == null || url.isBlank() ? null : stripSlash(url.trim());
        if (this.url == null) {
            this.http = null;
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.http = RestClient.builder().baseUrl(this.url).requestFactory(factory).build();
    }

    /**
     * The tokenize root for a chat base URL: {@code https://host/v1} →
     * {@code https://host}. vLLM serves {@code /tokenize} next to {@code /v1},
     * not inside it.
     */
    static String rootOf(String chatBaseUrl) {
        if (chatBaseUrl == null || chatBaseUrl.isBlank()) {
            return null;
        }
        String trimmed = stripSlash(chatBaseUrl.trim());
        return trimmed.endsWith("/v1") ? trimmed.substring(0, trimmed.length() - 3) : trimmed;
    }

    String url() {
        return url;
    }

    /** True while counting is configured and not backing off. */
    boolean available() {
        Instant until = downUntil;
        return http != null && (until == null || Instant.now().isAfter(until));
    }

    /** The text's token count, or empty when the tokenizer cannot be asked. */
    OptionalInt count(String text) {
        if (text == null || text.isEmpty()) {
            return OptionalInt.of(0);
        }
        if (!available()) {
            return OptionalInt.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", text);
            // The parts are summed and compared with the server's prompt
            // figure; a BOS token added to every part would be counted once
            // per message instead of once per prompt.
            body.put("add_special_tokens", false);
            String answer = http.post()
                    .uri("/tokenize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            JsonNode count = answer == null ? null : mapper.readTree(answer).get("count");
            if (count == null || !count.isNumber()) {
                throw new IllegalStateException("cevapta 'count' yok");
            }
            if (!Boolean.TRUE.equals(lastWorked)) {
                lastWorked = true;
                System.out.println("[uptest] ai-trace: /tokenize calisiyor (" + url
                        + ") — token kirilimi kesin");
            }
            return OptionalInt.of(count.asInt());
        } catch (RuntimeException failed) {
            downUntil = Instant.now().plus(BACK_OFF);
            if (!Boolean.FALSE.equals(lastWorked)) {
                lastWorked = false;
                System.out.println("[uptest] ai-trace: /tokenize kullanilamiyor (" + url + ": "
                        + failed.getClass().getSimpleName() + ": " + failed.getMessage()
                        + ") — kirilim tahmin (≈) olarak gosterilecek, 10 dk sonra tekrar denenecek");
            }
            return OptionalInt.empty();
        }
    }

    private static String stripSlash(String value) {
        String out = value;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
