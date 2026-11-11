package orchestrator.aitrace;

import java.time.Instant;
import java.util.List;

/**
 * One model call, as it happened, before anything is masked or stored.
 *
 * Built by {@link AiTraceObservationHandler} from Spring AI's observation for
 * every call that goes through a {@code ChatModel}, and by {@code VisionClient}
 * by hand, because the vision model is reached over plain HTTP and no
 * observation ever sees it.
 *
 * @param prompt       the server's prompt_tokens; null when it reported none
 * @param cached       prompt tokens served from the server's prefix cache;
 *                     null when the server does not report it, which is not 0
 * @param messages     the prompt, in order, one entry per message — a tool
 *                     response message is split into one entry per call
 * @param toolDefs     the tool definitions sent with the call, rendered as
 *                     text, or null when there were none
 * @param toolCalls    the tool calls the model asked for in its answer
 * @param vision       true when the prompt carried images, which no text
 *                     tokenizer can count — the breakdown is skipped
 * @param error        why the call failed, or null
 */
public record TurnRecord(Instant startedAt, long durationMs, String model,
                         Integer prompt, Integer completion, Integer total, Long cached,
                         String finishReason, List<Msg> messages, String toolDefs, int toolDefCount,
                         String response, String reasoning, List<Call> toolCalls,
                         boolean vision, String error) {

    public TurnRecord {
        messages = messages == null ? List.of() : List.copyOf(messages);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * One message of the prompt.
     *
     * @param role       system, user, assistant or tool
     * @param name       the tool's name, for a tool result
     * @param toolCallId which call a tool result answers
     */
    public record Msg(String role, String text, String name, String toolCallId) {

        public static Msg of(String role, String text) {
            return new Msg(role, text, null, null);
        }
    }

    /** A tool call the model requested in its answer. */
    public record Call(String id, String name, String arguments) {
    }
}
