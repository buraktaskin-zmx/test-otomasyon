package orchestrator.aitrace;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sees every model call Spring AI makes, including each turn of its own tool
 * loop.
 *
 * <p>Why an observation and not a wrapper: the MCP agent calls
 * {@code ChatClient.call()} ONCE and Spring AI loops inside — call the model,
 * run the tools it asked for, call the model again — until the model stops
 * asking. Nothing of ours runs between those turns except the tools, so the
 * turns, their token counts and what the model said in each were invisible.
 * {@code OpenAiChatModel} wraps every one of those calls in a
 * {@code ChatModelObservationContext} carrying the exact prompt it sent and
 * the response it got; registering this handler is the whole integration, and
 * the agent loop is not touched.
 *
 * <p>The record is built in {@link #onStop}, which Micrometer calls for a
 * failed call too (after {@code onError}), so a turn that threw is recorded
 * with its error rather than lost.
 */
final class AiTraceObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private static final String STARTED = AiTraceObservationHandler.class.getName() + ".started";
    private static final String NANOS = AiTraceObservationHandler.class.getName() + ".nanos";

    /** Where Spring AI's OpenAI model puts Qwen's / gpt-oss's reasoning text. */
    static final String REASONING = "reasoningContent";

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStart(ChatModelObservationContext context) {
        context.put(STARTED, Instant.now());
        context.put(NANOS, System.nanoTime());
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        try {
            Instant started = context.getOrDefault(STARTED, Instant.now());
            Long nanos = context.get(NANOS);
            long ms = nanos == null ? 0 : Duration.ofNanos(System.nanoTime() - nanos).toMillis();
            AiTraces.turn(record(context.getRequest(), context.getResponse(), context.getError(), started, ms));
        } catch (RuntimeException failed) {
            // A recording that cannot be built must not surface as a failed
            // model call; AiTraces logs its own failures, this is the rest.
            System.out.println("[uptest] ai-trace: tur okunamadi (" + failed.getClass().getSimpleName()
                    + ": " + failed.getMessage() + ")");
        }
    }

    /**
     * The call as a {@link TurnRecord}. Package-visible and static so it can
     * be tested on hand-built prompts and responses.
     */
    static TurnRecord record(Prompt prompt, ChatResponse response, Throwable error,
                             Instant started, long durationMs) {
        List<TurnRecord.Msg> messages = new ArrayList<>();
        String toolDefs = null;
        int toolDefCount = 0;
        String model = null;
        if (prompt != null) {
            for (Message message : prompt.getInstructions()) {
                messages.addAll(messagesOf(message));
            }
            ChatOptions options = prompt.getOptions();
            if (options != null) {
                model = options.getModel();
            }
            if (options instanceof ToolCallingChatOptions tooling && tooling.getToolCallbacks() != null
                    && !tooling.getToolCallbacks().isEmpty()) {
                toolDefCount = tooling.getToolCallbacks().size();
                toolDefs = renderDefinitions(tooling.getToolCallbacks());
            }
        }

        Integer promptTokens = null;
        Integer completion = null;
        Integer total = null;
        Long cached = null;
        String finish = null;
        String text = null;
        String reasoning = null;
        List<TurnRecord.Call> calls = new ArrayList<>();
        if (response != null) {
            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata != null) {
                if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                    model = metadata.getModel();
                }
                Usage usage = metadata.getUsage();
                if (usage != null && reported(usage)) {
                    promptTokens = usage.getPromptTokens();
                    completion = usage.getCompletionTokens();
                    total = usage.getTotalTokens();
                    cached = usage.getCacheReadInputTokens();
                }
            }
            Generation generation = response.getResult();
            if (generation != null) {
                AssistantMessage out = generation.getOutput();
                if (out != null) {
                    text = out.getText();
                    Object thought = out.getMetadata() == null ? null : out.getMetadata().get(REASONING);
                    if (thought != null && !String.valueOf(thought).isBlank()) {
                        reasoning = String.valueOf(thought);
                    }
                    for (AssistantMessage.ToolCall call : out.getToolCalls()) {
                        calls.add(new TurnRecord.Call(call.id(), call.name(), call.arguments()));
                    }
                }
                if (generation.getMetadata() != null) {
                    finish = generation.getMetadata().getFinishReason();
                }
            }
        }

        String failure = error == null ? null
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        return new TurnRecord(started, durationMs, model, promptTokens, completion, total, cached,
                finish, messages, toolDefs, toolDefCount, text, reasoning, calls, false, failure);
    }

    /**
     * One message as the trace stores it. A tool response message carries the
     * answers to every call of a turn; each becomes its own entry so the panel
     * can say which tool's result cost what.
     */
    static List<TurnRecord.Msg> messagesOf(Message message) {
        String role = message.getMessageType() == null ? "user"
                : message.getMessageType().getValue().toLowerCase(Locale.ROOT);
        if (message instanceof ToolResponseMessage tools) {
            List<TurnRecord.Msg> out = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse answer : tools.getResponses()) {
                out.add(new TurnRecord.Msg("tool", answer.responseData(), answer.name(), answer.id()));
            }
            return out;
        }
        if (message instanceof AssistantMessage assistant) {
            StringBuilder text = new StringBuilder(assistant.getText() == null ? "" : assistant.getText());
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append("→ ").append(call.name()).append(' ').append(call.arguments());
            }
            return List.of(TurnRecord.Msg.of("assistant", text.toString()));
        }
        return List.of(TurnRecord.Msg.of(role, message.getText()));
    }

    /** The tool definitions as the model receives them: name, description, argument schema. */
    static String renderDefinitions(List<ToolCallback> callbacks) {
        StringBuilder out = new StringBuilder();
        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            out.append("### ").append(definition.name()).append('\n');
            if (definition.description() != null) {
                out.append(definition.description()).append('\n');
            }
            out.append(definition.inputSchema()).append("\n\n");
        }
        return out.toString();
    }

    /** {@code EmptyUsage} answers zero for everything; that is "not reported", not "free". */
    private static boolean reported(Usage usage) {
        Integer total = usage.getTotalTokens();
        Integer prompt = usage.getPromptTokens();
        return (total != null && total > 0) || (prompt != null && prompt > 0);
    }
}
