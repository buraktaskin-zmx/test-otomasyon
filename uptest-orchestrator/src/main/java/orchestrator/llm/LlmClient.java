package orchestrator.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Chat client for the bank's vLLM endpoint, on Spring AI.
 *
 * Everything about WHERE it talks and WHICH model it asks for lives in
 * application.properties under spring.ai.openai.*; this class only owns the
 * shape of a call. That keeps the endpoint switch (direct vLLM vs bifrost
 * proxy) a configuration edit, never a code edit.
 */
@Service
public class LlmClient {

    private final ChatClient chat;

    public LlmClient(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    /** One user turn, one answer. No history, no system prompt — a smoke test. */
    public String ask(String userMessage) {
        return chat.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * A system turn carrying the rules and a user turn carrying the task.
     * Kept separate because the rules are long, identical for every request,
     * and belong where the model weighs them most heavily.
     */
    public String ask(String systemMessage, String userMessage) {
        ChatResponse response = chat.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .chatResponse();

        record(response);
        return (response == null || response.getResult() == null)
                ? ""
                : response.getResult().getOutput().getText();
    }

    /**
     * What the call actually cost, which nothing here used to know.
     *
     * {@code .call().content()} is the shorter spelling and it throws the
     * response metadata away, so the usage figures the server already sends
     * back — prompt, completion, total — were discarded on every call. That
     * left the only answer to "how many tokens does this spend?" an estimate
     * from prompt sizes, which is exactly the kind of guess this project
     * replaces with a measurement everywhere else.
     *
     * Logged rather than returned: {@link #ask} has several callers and
     * changing its signature for an observability concern would push the same
     * plumbing into all of them.
     *
     * Never throws. A server that omits usage is a reporting gap, not a failed
     * generation, and losing a finished scenario over a missing number would be
     * absurd.
     */
    private static void record(ChatResponse response) {
        Integer total = tokens(response);
        if (total == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        SPENT.addAndGet(total);
        System.out.println("[uptest] llm: prompt=" + usage.getPromptTokens()
                + " completion=" + usage.getCompletionTokens()
                + " total=" + total);
    }

    /** The call's total tokens, or null when the server reported none. */
    private static Integer tokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        return usage == null ? null : usage.getTotalTokens();
    }

    /**
     * Tokens spent since the application started.
     *
     * A counter rather than a per-call return value because the thing anyone
     * actually asks is "what did that batch cost", and a batch is five
     * concurrent calls from five threads. Reading it before and after a batch
     * and subtracting gives that answer without threading a token count through
     * every method between here and the controller.
     *
     * Approximate under concurrency ON PURPOSE: two batches running at once
     * would each see the other's tokens in their difference. That is the right
     * trade for a number shown to an analyst as "bu batch ~43.000 token
     * harcadı" — exactness here would cost a request-scoped context for no
     * decision it would change.
     */
    private static final AtomicLong SPENT = new AtomicLong();

    public static long spent() {
        return SPENT.get();
    }
}
