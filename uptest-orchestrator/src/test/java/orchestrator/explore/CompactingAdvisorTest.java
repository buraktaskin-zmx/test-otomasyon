package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompactingAdvisorTest {

    @Test
    @DisplayName("it sits inside the tool loop and in front of the model, and nowhere else")
    void orderIsInsideTheLoop() {
        int order = new CompactingAdvisor().getOrder();
        // ToolCallingAdvisor loops by calling the rest of the chain, so anything
        // ordered after it runs on EVERY turn; the model is last of all.
        assertThat(order)
                .isGreaterThan(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER)
                .isLessThan(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("the turn it passes on is the compacted one, and the request is otherwise untouched")
    void advisesWithTheCompactedPrompt() {
        var request = org.springframework.ai.chat.client.ChatClientRequest.builder()
                .prompt(walk()).context("k", "v").build();
        var seen = new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.client.ChatClientRequest>();
        var chain = new org.springframework.ai.chat.client.advisor.api.CallAdvisorChain() {
            @Override public org.springframework.ai.chat.client.ChatClientResponse nextCall(
                    org.springframework.ai.chat.client.ChatClientRequest passed) {
                seen.set(passed);
                return org.springframework.ai.chat.client.ChatClientResponse.builder().build();
            }
            @Override public List<org.springframework.ai.chat.client.advisor.api.CallAdvisor> getCallAdvisors() { return List.of(); }
            @Override public org.springframework.ai.chat.client.advisor.api.CallAdvisorChain copy(
                    org.springframework.ai.chat.client.advisor.api.CallAdvisor advisor) { return this; }
            @Override public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
                return io.micrometer.observation.ObservationRegistry.NOOP;
            }
        };

        new CompactingAdvisor().adviseCall(request, chain);

        List<Message> out = seen.get().prompt().getInstructions();
        assertThat(((ToolResponseMessage) out.get(3)).getResponses().get(0).responseData())
                .contains("[eski ekran kısaltıldı]");
        assertThat(((ToolResponseMessage) out.get(5)).getResponses().get(0).responseData())
                .isEqualTo(clicked("Güncelle"));
        assertThat(seen.get().context()).containsEntry("k", "v");
    }

    private static final String PAGE = "- row \"955231 222,00 TL Ödenmedi\" [ref=e31]\n".repeat(40);

    private static String clicked(String name) {
        return "### Ran Playwright code\n```js\nawait page.getByRole('button', { name: '" + name
                + "' }).click();\n```\n### Page\n- Page Snapshot:\n```yaml\n" + PAGE + "```";
    }

    private static ToolResponseMessage answer(String id, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "browser_click", data)))
                .build();
    }

    private static AssistantMessage asked(String id) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "browser_click", "{}")))
                .build();
    }

    private static Prompt walk() {
        return new Prompt(List.of(
                new SystemMessage("kurallar"),
                new UserMessage("Senaryo.\n\nŞu anki ekran:\n" + CompactingAdvisor.SCREEN_OPEN + "\n"
                        + PAGE + CompactingAdvisor.SCREEN_CLOSE),
                asked("c1"), answer("c1", clicked("Sorgula")),
                asked("c2"), answer("c2", clicked("Güncelle"))));
    }

    @Test
    @DisplayName("the latest page is sent whole; every earlier one shrinks to the line that was run")
    void keepsOnlyTheLatestPage() {
        List<Message> out = CompactingAdvisor.compact(walk()).getInstructions();

        assertThat(out).hasSize(6);
        ToolResponseMessage old = (ToolResponseMessage) out.get(3);
        ToolResponseMessage latest = (ToolResponseMessage) out.get(5);
        assertThat(old.getResponses().get(0).responseData())
                .contains("name: 'Sorgula'").contains("[eski ekran kısaltıldı]").doesNotContain("955231");
        assertThat(old.getResponses().get(0).id()).isEqualTo("c1");
        assertThat(latest.getResponses().get(0).responseData()).isEqualTo(clicked("Güncelle"));
    }

    @Test
    @DisplayName("the first screen in the user message goes too, once the agent has a newer page")
    void cutsTheFirstScreenOnceThereIsANewerPage() {
        List<Message> out = CompactingAdvisor.compact(walk()).getInstructions();
        String user = out.get(1).getText();
        assertThat(user).startsWith("Senaryo.").doesNotContain("955231")
                .doesNotContain(CompactingAdvisor.SCREEN_OPEN);
    }

    @Test
    @DisplayName("before any tool has answered, the prompt goes out untouched — the first screen included")
    void firstTurnIsUntouched() {
        Prompt first = new Prompt(List.of(new SystemMessage("k"),
                new UserMessage(CompactingAdvisor.SCREEN_OPEN + PAGE + CompactingAdvisor.SCREEN_CLOSE)));
        assertThat(CompactingAdvisor.compact(first)).isSameAs(first);
    }

    @Test
    @DisplayName("a short result is kept as it is; a long one with no code says where the page went")
    void stubRules() {
        assertThat(CompactingAdvisor.stub("Clicked")).isEqualTo("Clicked");
        // No Playwright echo, because LeanResults cuts it out of what the
        // model is handed. Keeping the first line of a page ("### Page state")
        // said nothing; the call itself is in the assistant message above it.
        String long_ = "### Page state\n" + "x".repeat(500);
        assertThat(CompactingAdvisor.stub(long_))
                .isEqualTo("[eski ekran kısaltıldı — güncel ekran son araç sonucunda]");
    }
}
