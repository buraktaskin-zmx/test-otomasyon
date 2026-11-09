package orchestrator.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The model that READS A SCREENSHOT. Its own endpoint, its own key, its own
 * HTTP call.
 *
 * <p><b>Why its own client.</b> There is one auto-configured
 * {@code ChatClient.Builder} and two consumers, {@link LlmClient} and the MCP
 * agent. The vision model lives on the bifrost proxy, and application.properties
 * records what happens when the agent is pointed there: the proxy's tool-call
 * parser does not match qwen, the agent's calls arrive as plain text and no
 * tool ever runs. Reconfiguring the shared endpoint would break the agent.
 *
 * <p><b>Why plain HTTP rather than Spring AI.</b> Spring AI 2.0 wraps the
 * official {@code com.openai} SDK, and pointing a second
 * {@code OpenAiChatModel} at another base URL means going through
 * {@code OpenAiSetup.setupSyncClient}, a sixteen-argument method built for
 * the auto-configuration to call. Against that, the request here is one fixed
 * shape — one system turn, one user turn carrying text and an image — and it
 * is a shape we have already seen work against this exact proxy. Writing the
 * fifteen lines of JSON is less code and far less to go wrong than bending an
 * abstraction we use one percent of.
 *
 * <p>Off unless configured: {@code @ConditionalOnProperty} on
 * {@code uptest.vision.enabled}, so an install with no vision endpoint simply
 * has no bean and the feature's endpoints answer 404 — the shape
 * {@code uptest.explore.enabled} already uses for the agent.
 */
@Service
@ConditionalOnProperty(name = "uptest.vision.enabled", havingValue = "true")
public class VisionClient {

    /**
     * Reading a screen is not a creative task: the same screenshot must give
     * the same field list, or the analyst's correction of yesterday's draft
     * means nothing today.
     */
    private static final double TEMPERATURE = 0.0;

    private static final int MAX_TOKENS = 2000;

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;

    public VisionClient(@Value("${uptest.vision.base-url}") String baseUrl,
                        @Value("${uptest.vision.api-key}") String apiKey,
                        @Value("${uptest.vision.model}") String model,
                        @Value("${uptest.vision.timeout:120s}") Duration timeout,
                        ObjectMapper mapper) {
        // Loud at startup rather than a 401 on the analyst's first click. The
        // key is deliberately NOT in application.properties — that file is
        // committed and this repo has already leaked one — so an install that
        // turned the feature on without supplying it is a setup mistake, and
        // the message says exactly where to put it.
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "uptest.vision.enabled=true ama uptest.vision.api-key boş. "
                            + "Anahtarı application-local.properties dosyasına ya da "
                            + "UPTEST_VISION_API_KEY ortam değişkenine koyun; "
                            + "application.properties'e YAZMAYIN.");
        }
        this.mapper = mapper;
        this.model = model;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // A screenshot plus a long answer is slow; the default would cut it off
        // mid-read and report a network error for a working endpoint.
        factory.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        this.http = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                // The proxy wants its virtual key in x-bf-vk but also accepts
                // the standard header, which is what every other client here
                // sends; one way in is one less thing to get wrong.
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();
    }

    public String model() {
        return model;
    }

    /**
     * One screenshot, one question, one answer.
     *
     * @param png the image bytes — PNG, which is what a Windows screen capture
     *            pastes and what the UI sends
     * @throws IllegalStateException when the endpoint refuses or answers
     *                               something that is not a completion; the
     *                               caller turns it into one screen's error
     *                               rather than failing the batch
     */
    public String ask(String systemMessage, String userMessage, byte[] png) {
        return ask(systemMessage, userMessage, List.of(png));
    }

    /**
     * Several captures of ONE screen, one question, one answer.
     *
     * <p>They go in a single call rather than one call each because they are
     * parts of the same page: a form whose lower half is in the second
     * picture has to be read as one form, and two separate answers would have
     * to be merged by something that cannot tell a repeated field from a new
     * one. The cost is linear in pictures, which is why
     * {@code FlowStore.MAX_IMAGES_PER_NODE} caps them.
     *
     * <p>Order is preserved and stated in the user turn: these are top to
     * bottom of one page, not alternatives.
     */
    public String ask(String systemMessage, String userMessage, List<byte[]> images) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userMessage));
        for (byte[] png : images) {
            content.add(Map.of("type", "image_url", "image_url", Map.of(
                    "url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png))));
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                "temperature", TEMPERATURE,
                "messages", List.of(
                        Map.of("role", "system", "content", systemMessage),
                        Map.of("role", "user", "content", content)));

        String answer;
        try {
            answer = http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException failed) {
            throw new IllegalStateException("Görsel model çağrısı başarısız: " + failed.getMessage(), failed);
        }

        return content(answer);
    }

    /** The assistant's text, plus the usage figures into the shared counter. */
    private String content(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalStateException("Görsel model boş cevap verdi.");
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Görsel modelin cevabı JSON değil: " + head(rawJson));
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("Görsel model hatası: " + error.toString());
        }
        record(root);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("Görsel model hiç cevap döndürmedi: " + head(rawJson));
        }
        JsonNode message = choices.get(0).path("message").path("content");
        return message.isNull() ? "" : message.asString("");
    }

    /** Same accounting as {@link LlmClient}, into the same process counter. */
    private static void record(JsonNode root) {
        JsonNode usage = root.get("usage");
        if (usage == null || usage.isNull()) {
            return;
        }
        int total = usage.path("total_tokens").asInt(0);
        if (total <= 0) {
            return;
        }
        LlmClient.addSpent(total);
        System.out.println("[uptest] vision: prompt=" + usage.path("prompt_tokens").asInt(0)
                + " completion=" + usage.path("completion_tokens").asInt(0)
                + " total=" + total);
    }

    private static String head(String text) {
        String clean = text.strip().replace("\n", " ");
        return clean.length() > 300 ? clean.substring(0, 300) + "…" : clean;
    }
}
