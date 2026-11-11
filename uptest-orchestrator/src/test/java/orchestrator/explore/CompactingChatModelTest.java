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

class CompactingChatModelTest {

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
                new UserMessage("Senaryo.\n\nŞu anki ekran:\n" + CompactingChatModel.SCREEN_OPEN + "\n"
                        + PAGE + CompactingChatModel.SCREEN_CLOSE),
                asked("c1"), answer("c1", clicked("Sorgula")),
                asked("c2"), answer("c2", clicked("Güncelle"))));
    }

    @Test
    @DisplayName("the latest page is sent whole; every earlier one shrinks to the line that was run")
    void keepsOnlyTheLatestPage() {
        List<Message> out = CompactingChatModel.compact(walk()).getInstructions();

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
        List<Message> out = CompactingChatModel.compact(walk()).getInstructions();
        String user = out.get(1).getText();
        assertThat(user).startsWith("Senaryo.").doesNotContain("955231")
                .doesNotContain(CompactingChatModel.SCREEN_OPEN);
    }

    @Test
    @DisplayName("before any tool has answered, the prompt goes out untouched — the first screen included")
    void firstTurnIsUntouched() {
        Prompt first = new Prompt(List.of(new SystemMessage("k"),
                new UserMessage(CompactingChatModel.SCREEN_OPEN + PAGE + CompactingChatModel.SCREEN_CLOSE)));
        assertThat(CompactingChatModel.compact(first)).isSameAs(first);
    }

    @Test
    @DisplayName("a short result is kept as it is; a long one with no code keeps its first line")
    void stubRules() {
        assertThat(CompactingChatModel.stub("Clicked")).isEqualTo("Clicked");
        String long_ = "Sonuç satırı\n" + "x".repeat(500);
        assertThat(CompactingChatModel.stub(long_)).startsWith("Sonuç satırı").contains("kısaltıldı");
    }
}
