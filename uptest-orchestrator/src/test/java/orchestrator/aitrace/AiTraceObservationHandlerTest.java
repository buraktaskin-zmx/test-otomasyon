package orchestrator.aitrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The turn as the agent's tool loop sends it: system, user, the model's
 * earlier tool call, and the tools' answers.
 */
class AiTraceObservationHandlerTest {

    private static Prompt agentTurn() {
        AssistantMessage earlier = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "browser_snapshot", "{}"),
                        new AssistantMessage.ToolCall("c2", "function", "browser_click", "{\"target\":\"e7\"}")))
                .build();
        ToolResponseMessage answers = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "browser_snapshot", "- button \"Devam\" [ref=e7]"),
                        new ToolResponseMessage.ToolResponse("c2", "browser_click", "Clicked")))
                .build();
        return new Prompt(List.of(new SystemMessage("kurallar"), new UserMessage("talimat"), earlier, answers));
    }

    private static ChatResponse answer() {
        AssistantMessage out = AssistantMessage.builder()
                .content("Onay ekranına geçtim.")
                .properties(Map.of(AiTraceObservationHandler.REASONING, "Önce Devam'a basmalıyım"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("c3", "function", "browser_snapshot", "{}")))
                .build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(out, ChatGenerationMetadata.builder().finishReason("tool_calls").build())))
                .metadata(ChatResponseMetadata.builder().model("qwen3-6").usage(new DefaultUsage(1200, 80, 1280)).build())
                .build();
    }

    @Test
    @DisplayName("each tool answer is its own message, and the earlier tool calls are part of the history")
    void splitsToolResponsesAndKeepsHistory() {
        TurnRecord turn = AiTraceObservationHandler.record(agentTurn(), answer(), null, Instant.now(), 900);

        assertThat(turn.messages()).extracting(TurnRecord.Msg::role)
                .containsExactly("system", "user", "assistant", "tool", "tool");
        assertThat(turn.messages().get(2).text()).contains("→ browser_click {\"target\":\"e7\"}");
        assertThat(turn.messages().get(3).name()).isEqualTo("browser_snapshot");
        assertThat(turn.messages().get(4).toolCallId()).isEqualTo("c2");
    }

    @Test
    @DisplayName("the server's figures, the answer, its reasoning and the calls it asks for are all read")
    void readsUsageAnswerReasoningAndCalls() {
        TurnRecord turn = AiTraceObservationHandler.record(agentTurn(), answer(), null, Instant.now(), 900);

        assertThat(turn.prompt()).isEqualTo(1200);
        assertThat(turn.completion()).isEqualTo(80);
        assertThat(turn.total()).isEqualTo(1280);
        assertThat(turn.model()).isEqualTo("qwen3-6");
        assertThat(turn.finishReason()).isEqualTo("tool_calls");
        assertThat(turn.response()).isEqualTo("Onay ekranına geçtim.");
        assertThat(turn.reasoning()).isEqualTo("Önce Devam'a basmalıyım");
        assertThat(turn.toolCalls()).extracting(TurnRecord.Call::name).containsExactly("browser_snapshot");
        assertThat(turn.durationMs()).isEqualTo(900);
        assertThat(turn.vision()).isFalse();
    }

    @Test
    @DisplayName("a call that failed before any answer is still a turn, with its error")
    void failedCallIsRecorded() {
        TurnRecord turn = AiTraceObservationHandler.record(agentTurn(), null,
                new IllegalStateException("502 Bad Gateway"), Instant.now(), 30_000);

        assertThat(turn.error()).isEqualTo("IllegalStateException: 502 Bad Gateway");
        assertThat(turn.prompt()).isNull();
        assertThat(turn.messages()).hasSize(5);
    }

    @Test
    @DisplayName("zero usage means the server reported none, not that the call was free")
    void emptyUsageIsNotReported() {
        ChatResponse silent = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("x"))))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0, 0)).build())
                .build();
        TurnRecord turn = AiTraceObservationHandler.record(new Prompt("merhaba"), silent, null, Instant.now(), 1);
        assertThat(turn.prompt()).isNull();
        assertThat(turn.total()).isNull();
    }
}
