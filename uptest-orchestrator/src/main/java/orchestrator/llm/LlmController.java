package orchestrator.llm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smoke-test endpoint for the LLM link. Not part of the analyst API — it exists
 * so "is the model reachable from this host?" can be answered with a browser
 * hit instead of a debugging session, the same role GET /cucumber/test plays
 * for the executor.
 *
 * Failures are returned as 200 with an "error" field rather than left to the
 * default 500 page. The whole point of a smoke test is to name what went wrong;
 * a Whitelabel Error Page sends you to the console to find out whether it was a
 * certificate, a proxy or a 404. Acceptable here precisely because this
 * endpoint is diagnostic and reaches nothing sensitive — the real API
 * (/api/v1/scenarios/...) keeps proper status codes.
 *
 * Scenario generation will NOT live here: it belongs behind
 * POST /api/v1/scenarios/suggest, which must run its draft through
 * ScenarioValidator before anything reaches an analyst.
 */
@RestController
@RequestMapping("/api/v1/llm")
public class LlmController {

    private final LlmClient llm;

    public LlmController(LlmClient llm) {
        this.llm = llm;
    }

    @GetMapping("/hello")
    public Map<String, String> hello(
            @RequestParam(defaultValue = "Merhaba! Tek cümleyle kendini tanıt.") String prompt) {

        Map<String, String> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        try {
            body.put("answer", llm.ask(prompt));
        } catch (Exception e) {
            // The root cause carries the useful text: PKIX for a certificate,
            // "Connection refused" for a proxy, the status line for a 404/401.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            body.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            body.put("rootCause", root.getClass().getName() + ": " + root.getMessage());
        }
        return body;
    }
}
