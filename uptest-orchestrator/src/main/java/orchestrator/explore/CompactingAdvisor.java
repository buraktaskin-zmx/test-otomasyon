package orchestrator.explore;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

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
 * because the tool loop resends the WHOLE conversation every turn, so the
 * snapshot of turn 3 was paid for again on turns 4 through 52.
 *
 * <p>An old snapshot is worth nothing to the model: its references die the
 * moment the page changes, and the page has changed. So on every turn this
 * keeps the LAST tool response whole — the screen the agent is acting on — and
 * cuts every earlier one to the Playwright line that was run. The record of
 * what was DONE survives; the pages it was done on do not.
 *
 * <p><b>An advisor, not a ChatModel wrapper.</b> The first version wrapped the
 * model and gave "AI ile çalıştır" its own hand-built ChatClient. That client
 * is not the one Spring configures, and the options it produced were a plain
 * {@code DefaultChatOptions}; {@code OpenAiChatModel.createRequest} casts them
 * to {@code OpenAiChatOptions}, so every walk died with a ClassCastException
 * before the agent moved. An advisor needs no second client: it is added to
 * the same working one per request.
 *
 * <p><b>Why this runs on every turn.</b> {@code ToolCallingAdvisor} loops by
 * calling the rest of the chain again ({@code DEFAULT_ORDER} =
 * {@code MIN_VALUE + 300}), and the model sits at the end of it
 * ({@code ChatModelCallAdvisor}, {@code MAX_VALUE}). An order between the two
 * is therefore inside the loop and ahead of the model — every turn, just
 * before it is sent.
 *
 * <p>What is cut is only what is SENT: the action tape keeps every result
 * whole (BudgetedTool records it before this ever runs), and "Gherkin oluştur"
 * reads the tape.
 *
 * <p>The first screen after the login is handed over in the user message,
 * between {@link #SCREEN_OPEN} and {@link #SCREEN_CLOSE}. The user message is
 * resent every turn and is not a tool response, so that block is cut the same
 * way once the agent has a newer page.
 */
final class CompactingAdvisor implements CallAdvisor {

    /** Between ToolCallingAdvisor (outermost) and the model (innermost). */
    private static final int ORDER = 0;

    @Override
    public String getName() {
        return "uptest-compacting";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Prompt sent = compact(request.prompt());
        report(request.prompt(), sent);
        return chain.nextCall(request.mutate().prompt(sent).build());
    }

    static final String SCREEN_OPEN = "<<EKRAN>>";
    static final String SCREEN_CLOSE = "<</EKRAN>>";

    private static final Pattern SCREEN = Pattern.compile(
            Pattern.quote(SCREEN_OPEN) + ".*?" + Pattern.quote(SCREEN_CLOSE), Pattern.DOTALL);

    /** A result this short costs less than the note that would replace it. */
    static final int KEEP_UNDER = 300;

    /**
     * One line per turn: that this wrapper was reached at all, what the client
     * handed it, and what the compaction saved.
     *
     * The options class is on it deliberately. Spring builds the request's
     * options somewhere between the ChatClient and here, and which concrete
     * type arrives decides whether the model below can read the tools off it —
     * a question that cost a whole debugging round to ask from the outside.
     * Printing it makes the answer the first thing in the log.
     */
    private static void report(Prompt before, Prompt after) {
        int shortened = 0;
        long was = 0;
        long now = 0;
        for (int i = 0; i < before.getInstructions().size(); i++) {
            if (!(before.getInstructions().get(i) instanceof ToolResponseMessage from)) {
                continue;
            }
            ToolResponseMessage to = (ToolResponseMessage) after.getInstructions().get(i);
            for (int r = 0; r < from.getResponses().size(); r++) {
                String one = from.getResponses().get(r).responseData();
                String two = to.getResponses().get(r).responseData();
                if (one != null && two != null && two.length() < one.length()) {
                    shortened++;
                    was += one.length();
                    now += two.length();
                }
            }
        }
        System.out.println("[uptest] compact: " + before.getInstructions().size() + " mesaj, "
                + shortened + " eski sonuc kisaltildi (" + was + " -> " + now + " karakter), options="
                + (before.getOptions() == null ? "yok" : before.getOptions().getClass().getSimpleName()));
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
     * What an old result becomes: a note saying the page is gone.
     *
     * It used to keep the result's FIRST LINE when there was no Playwright
     * echo to keep, and that stopped meaning anything once LeanResults began
     * cutting the echo out of what the model is handed: the first line of a
     * page is now "### Page state", which says nothing about which call it
     * belonged to. It does not need to — the assistant message directly above
     * the result IS the tool call, with its arguments. So the stub says the
     * one thing written nowhere else: the page it stands for is gone, and
     * where the current one is.
     *
     * The echo branch stays for results that still carry one.
     */
    static String stub(String data) {
        if (data == null || data.length() < KEEP_UNDER) {
            return data;
        }
        String code = TapeEvidence.code(data);
        if (!code.isBlank()) {
            return code + "\n[eski ekran kısaltıldı]";
        }
        return "[eski ekran kısaltıldı — güncel ekran son araç sonucunda]";
    }
}
