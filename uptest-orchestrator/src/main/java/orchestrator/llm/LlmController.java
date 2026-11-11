package orchestrator.llm;

import orchestrator.aitrace.AiTraces;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

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
        // Recorded as "health" so Token Takibi can leave it out by default:
        // the UI asks this once on every page load.
        AiTraces.Scope trace = AiTraces.open("health", new AiTraces.Meta().title("Model sağlık kontrolü"));
        try {
            body.put("answer", llm.ask(prompt));
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            body.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            body.put("rootCause", root.getClass().getName() + ": " + root.getMessage());
            trace.fail(body.get("error"));
        } finally {
            trace.close();
        }
        return body;
    }
}
