package orchestrator.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Chat client for the bank's vLLM endpoint, on Spring AI.
 *
 * Everything about WHERE it talks and WHICH model it asks for lives in
 * application.properties under spring.ai.openai.*; this class only owns the
 * shape of a call. That keeps the endpoint switch (direct vLLM vs bifrost
 * proxy) a configuration edit, never a code edit.
 *
 * "OpenAI" in the starter name is the WIRE PROTOCOL, not a destination. vLLM
 * serves Qwen behind the same /v1/chat/completions schema, so pointing
 * base-url at an internal host is all that is required and no traffic leaves
 * the bank.
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
        return chat.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .content();
    }
}
