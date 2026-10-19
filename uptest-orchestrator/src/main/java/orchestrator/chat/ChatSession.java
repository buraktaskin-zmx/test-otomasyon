package orchestrator.chat;

import orchestrator.validation.ValidationResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One conversation, in memory for as long as the analyst is having it.
 *
 * NOT persisted, and that is a decision rather than a gap. TestRunEntity
 * already records the hazard in its own javadoc: free text is an uncontrolled
 * channel, and an analyst who types "12345678 numaralı müşteri için fatura"
 * has written a CIF down where no regex can find it. A chat transcript is that
 * same channel with more turns. The scenario it produces goes through the
 * ordinary path, where Redactor already runs.
 *
 * Single-user by construction: one analyst, one browser tab, one session id.
 * The only field another thread reads is {@link #touched}, for the idle sweep.
 */
public final class ChatSession {

    /** One line of the conversation. {@code role} is "ai" or "you". */
    public record Message(String role, String text) {
    }

    private final String id;
    private final String app;
    private final String flow;
    private final String cif;
    private final String userid;

    /**
     * The product chosen when the conversation opened, and the rules ticked
     * with it. Fixed for the session's life: a conversation is about one
     * screen under one rule book, and letting either change mid-thread would
     * mean the answers already given were given about something else.
     */
    private final String product;
    private final List<String> ruleIds;

    private final List<Message> messages = new ArrayList<>();

    /**
     * The request, built up a sentence at a time.
     *
     * Every answer APPENDS rather than replaces, so the model sees the whole
     * agreement on every turn: the original intent, plus "«Bayi Ünvanı» alanı
     * «TEST / TEST UNVANI» yazmalı", plus whatever came after. A chat that
     * re-sent only the last answer would regenerate a scenario that had
     * forgotten the first one.
     */
    private final List<String> request = new ArrayList<>();

    private String feature;
    private ValidationResult validation;
    private ChatQuestions.Resolution pending;

    private volatile Instant touched = Instant.now();

    ChatSession(String id, String app, String flow, String cif, String userid,
                String product, List<String> ruleIds) {
        this.id = id;
        this.app = app;
        this.flow = flow;
        this.cif = cif;
        this.userid = userid;
        this.product = product;
        this.ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    public String id() {
        return id;
    }

    public String app() {
        return app;
    }

    public String flow() {
        return flow;
    }

    public String cif() {
        return cif;
    }

    public String userid() {
        return userid;
    }

    public String product() {
        return product;
    }

    public List<String> ruleIds() {
        return ruleIds;
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public void say(String role, String text) {
        if (text != null && !text.isBlank()) {
            messages.add(new Message(role, text.trim()));
        }
    }

    public void ask(String text) {
        say("ai", text);
    }

    /** Adds a sentence to the request. */
    public void add(String sentence) {
        if (sentence != null && !sentence.isBlank()) {
            request.add(sentence.trim());
        }
    }

    /** The whole agreement so far, as the model is given it. */
    public String request() {
        return String.join("\n", request);
    }

    public boolean hasRequest() {
        return !request.isEmpty();
    }

    public String feature() {
        return feature;
    }

    public ValidationResult validation() {
        return validation;
    }

    public ChatQuestions.Resolution pending() {
        return pending;
    }

    public void record(String draft, ValidationResult result, ChatQuestions.Resolution question) {
        this.feature = draft;
        this.validation = result;
        this.pending = question;
    }

    Instant touched() {
        return touched;
    }

    void touch() {
        this.touched = Instant.now();
    }
}
