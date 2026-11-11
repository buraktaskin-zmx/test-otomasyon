package orchestrator.explore;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends the model only the page it is looking at now, not every page it has
 * ever seen.
 *
 * <p><b>Measured, 11.11.2026:</b> one "AI ile çalıştır" walk took 52 turns and
 * 1.48 million input tokens, and 66% of them were tool results — not because
 * the results were large (a snapshot is ~2,200 tokens, a click ~160) but
 * because Spring AI's tool loop resends the WHOLE conversation every turn, so
 * the snapshot of turn 3 was paid for again on turns 4 through 52.
 *
 * <p>An old snapshot is worth nothing to the model: its references die the
 * moment the page changes, and the page has changed. So on every call this
 * keeps the LAST tool response whole — the screen the agent is acting on — and
 * cuts every earlier one to the Playwright line that was run. The record of
 * what was DONE survives; the pages it was done on do not.
 *
 * <p>It sits between {@code ChatClient}'s tool loop and the real model:
 * {@code ToolCallingAdvisor} calls {@code chatModel.call} once per turn through
 * {@code ChatModelCallAdvisor}, so wrapping the model is enough to see every
 * turn, and the loop, its guards and {@code report_result}'s returnDirect are
 * left exactly as they are. What is cut is only what is SENT: the action tape
 * keeps every result whole (BudgetedTool records it before this ever runs),
 * and "Gherkin oluştur" reads the tape.
 *
 * <p>The first screen after the login is handed over in the user message,
 * between {@link #SCREEN_OPEN} and {@link #SCREEN_CLOSE}. The user message is
 * resent every turn and is not a tool response, so that block is cut the same
 * way once the agent has a newer page.
 */
final class CompactingChatModel implements ChatModel {

    static final String SCREEN_OPEN = "<<EKRAN>>";
    static final String SCREEN_CLOSE = "<</EKRAN>>";

    private static final Pattern SCREEN = Pattern.compile(
            Pattern.quote(SCREEN_OPEN) + ".*?" + Pattern.quote(SCREEN_CLOSE), Pattern.DOTALL);

    /** A result this short costs less than the note that would replace it. */
    static final int KEEP_UNDER = 300;

    private final ChatModel delegate;

    CompactingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(compact(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(compact(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /**
     * The same prompt, same messages in the same order, with every tool
     * response but the last cut down. Ids and names are kept, so each result
     * still answers the call that asked for it.
     */
    static Prompt compact(Prompt prompt) {
        List<Message> in = prompt.getInstructions();
        int lastTool = -1;
        for (int i = 0; i < in.size(); i++) {
            if (in.get(i) instanceof ToolResponseMessage) {
                lastTool = i;
            }
        }
        if (lastTool < 0) {
            return prompt;
        }
        List<Message> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            Message message = in.get(i);
            if (message instanceof ToolResponseMessage tools && i != lastTool) {
                out.add(shorten(tools));
            } else if (message instanceof UserMessage user && hasScreen(user.getText())) {
                out.add(UserMessage.builder()
                        .text(SCREEN.matcher(user.getText()).replaceAll(
                                Matcher.quoteReplacement("[giriş sonrası ilk ekran kısaltıldı — "
                                        + "güncel ekran son araç sonucunda]")))
                        .metadata(user.getMetadata())
                        .build());
            } else {
                out.add(message);
            }
        }
        return new Prompt(out, prompt.getOptions());
    }

    private static boolean hasScreen(String text) {
        return text != null && text.contains(SCREEN_OPEN);
    }

    private static ToolResponseMessage shorten(ToolResponseMessage message) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            responses.add(new ToolResponseMessage.ToolResponse(
                    response.id(), response.name(), stub(response.responseData())));
        }
        return ToolResponseMessage.builder()
                .responses(responses)
                .metadata(message.getMetadata())
                .build();
    }

    /**
     * What an old result becomes: the Playwright line it ran, or its first
     * line, and a note saying the page itself is gone.
     */
    static String stub(String data) {
        if (data == null || data.length() < KEEP_UNDER) {
            return data;
        }
        String code = TapeEvidence.code(data);
        if (!code.isBlank()) {
            return code + "\n[eski ekran kısaltıldı]";
        }
        String first = data.strip().lines().findFirst().orElse("");
        if (first.length() > 200) {
            first = first.substring(0, 200) + "…";
        }
        return first + "\n[eski sonuç kısaltıldı: " + data.length() + " karakter]";
    }
}
