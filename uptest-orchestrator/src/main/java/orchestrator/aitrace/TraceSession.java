package orchestrator.aitrace;

import orchestrator.persistence.AiSessionEntity;
import orchestrator.persistence.AiToolCallEntity;
import orchestrator.persistence.AiTurnEntity;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One open AI session, while it runs.
 *
 * <p>Thread-safe because a "Senaryo Üret" batch writes into ONE session from
 * four worker threads at once. Every update to the counters and the session
 * row happens under this object's lock, so the row written last is always the
 * sum of everything before it.
 *
 * <p><b>Which turn a tool call belongs to.</b> Spring AI runs the tools of a
 * turn on the thread that made the call, after the turn's answer and before
 * the next turn starts. So the turn this THREAD finished last is the one that
 * asked for the call — per thread, because in a parallel batch "the last
 * turn" of the session belongs to someone else's worker.
 *
 * <p><b>What is masked.</b> Every text passes {@link SecretMask} before it is
 * stored, with the action tape's two rules: prose the analyst or the model
 * wrote (system, user, the model's answer, its reasoning) by the loose rule,
 * because the composed instruction carries {@code - Şifre = …} unquoted; tool
 * arguments and results by the anchored rule, so a page value that merely
 * contains the password's digits survives.
 */
final class TraceSession {

    static final int PREVIEW = 1000;

    final UUID id;
    private final AiSessionEntity row;
    private final SecretMask mask;
    private final TraceSink sink;
    private final long startNanos = System.nanoTime();

    /** Every text this session has sent the model, so a turn can say which of its messages were new. */
    private final Set<String> sent = ConcurrentHashMap.newKeySet();
    private final Map<Thread, Integer> lastTurnOfThread = new ConcurrentHashMap<>();
    private final Map<String, TraceFiles.Count> counts = new ConcurrentHashMap<>();
    private final Set<String> models = new LinkedHashSet<>();

    private int turnSeq;
    private int toolSeq;

    /**
     * Characters per token, measured on this session's own first turn: its
     * whole prompt's characters over the prompt tokens the server reported.
     * Only the fallback estimate uses it, and it is this session's language
     * and this model's tokenizer rather than a constant someone picked.
     */
    private volatile Double charsPerToken;

    private boolean complained;
    private boolean finished;

    TraceSession(UUID id, String kind, AiTraces.Meta meta, UUID parentId, TraceSink sink) {
        this.id = id;
        this.sink = sink;
        this.mask = SecretMask.of(meta.secrets());
        this.row = new AiSessionEntity(id, kind, Instant.now());
        row.setTitle(cut(mask.maskText(meta.title()), 512));
        row.setApp(cut(meta.app(), 64));
        row.setProduct(cut(meta.product(), 64));
        row.setFlow(cut(meta.flow(), 128));
        row.setScenarioPath(cut(meta.scenarioPath(), 512));
        row.setCif(cut(meta.cif(), 64));
        row.setTapeId(cut(meta.tapeId(), 64));
        row.setParentId(parentId);
        sink.saveSession(row);
        header();
    }

    String kind() {
        return row.getKind();
    }

    /** Prompt plus completion tokens so far, as the server reported them. */
    synchronized long totalTokens() {
        return row.getTotalTokens();
    }

    /** Records one model call; returns its sequence number in this session. */
    int turn(TurnRecord call) {
        List<Ref> refs = new ArrayList<>();
        Ref defs;
        AiTurnEntity turn;
        int seq;
        synchronized (this) {
            seq = ++turnSeq;
            lastTurnOfThread.put(Thread.currentThread(), seq);

            int fresh = 0;
            long promptChars = 0;
            for (TurnRecord.Msg message : call.messages()) {
                String text = maskFor(message.role(), message.text());
                String sha = put(text);
                boolean isNew = sha != null && sent.add(sha);
                if (isNew) {
                    fresh++;
                }
                promptChars += text == null ? 0 : text.length();
                refs.add(new Ref(message.role(), message.name(), message.toolCallId(), sha, text, isNew));
            }
            String defsText = call.toolDefs();
            defs = defsText == null ? null : new Ref("tool_defs", null, null, put(defsText), defsText, false);
            promptChars += defsText == null ? 0 : defsText.length();

            String response = mask.maskText(call.response());
            String reasoning = mask.maskText(call.reasoning());

            turn = new AiTurnEntity(UUID.randomUUID(), id, seq, call.startedAt());
            turn.setDurationMs(call.durationMs());
            turn.setModel(cut(call.model(), 256));
            turn.setPromptTokens(call.prompt());
            turn.setCompletionTokens(call.completion());
            turn.setTotalTokens(totalOf(call));
            turn.setCachedTokens(call.cached());
            turn.setFinishReason(cut(call.finishReason(), 64));
            turn.setMessageCount(call.messages().size());
            turn.setNewMessageCount(fresh);
            turn.setToolDefCount(call.toolDefCount());
            turn.setRequestedToolCalls(call.toolCalls().size());
            turn.setResponseChars(response == null ? 0 : response.length());
            turn.setResponsePreview(cut(response, PREVIEW));
            turn.setResponseSha(put(response));
            turn.setReasoningChars(reasoning == null ? 0 : reasoning.length());
            turn.setReasoningSha(put(reasoning));
            turn.setErrorMessage(call.error());
            sink.saveTurn(turn);

            List<Map<String, Object>> calls = new ArrayList<>();
            for (TurnRecord.Call requested : call.toolCalls()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", requested.id());
                entry.put("name", requested.name());
                entry.put("arguments", mask.maskValues(requested.arguments()));
                calls.add(entry);
            }
            writeTurn(seq, call, refs, defs, turn, calls);

            if (call.prompt() != null) {
                row.setPromptTokens(row.getPromptTokens() + call.prompt());
                if (charsPerToken == null && !call.vision() && call.prompt() > 0 && promptChars > 0) {
                    charsPerToken = (double) promptChars / call.prompt();
                }
            }
            if (call.completion() != null) {
                row.setCompletionTokens(row.getCompletionTokens() + call.completion());
            }
            Integer total = totalOf(call);
            if (total != null) {
                row.setTotalTokens(row.getTotalTokens() + total);
            }
            if (call.cached() != null) {
                row.setCachedTokens((row.getCachedTokens() == null ? 0 : row.getCachedTokens()) + call.cached());
            }
            if (call.model() != null && !call.model().isBlank() && models.add(call.model())) {
                row.setModels(cut(String.join(", ", models), 512));
            }
            row.setTurnCount(seq);
            row.setDurationMs(elapsedMs());
            sink.saveSession(row);
        }
        System.out.println("[uptest] ai-trace " + row.getKind() + " " + shortId() + " tur " + seq
                + ": prompt=" + call.prompt() + " completion=" + call.completion()
                + (call.cached() == null ? "" : " cached=" + call.cached())
                + " tool=" + call.toolCalls().size() + " (" + call.durationMs() + " ms)"
                + (call.error() == null ? "" : " HATA: " + call.error()));

        if (!call.vision()) {
            UUID turnId = turn.getId();
            Integer prompt = call.prompt();
            sink.later(() -> breakdown(turnId, refs, defs, prompt));
        }
        return seq;
    }

    /**
     * Records one tool call.
     *
     * @param result what the tool returned, whole — the tape's evidence
     * @param seen   what the model was actually given back, after BudgetedTool
     *               trimmed it; its tokens are the ones the next turn pays
     *               for. Null when the call threw.
     */
    void tool(String name, String args, String outcome, String result, String seen, Long durationMs) {
        String maskedArgs = mask.maskValues(args);
        String maskedResult = mask.maskValues(result);
        String maskedSeen = seen == null ? maskedResult : mask.maskValues(seen);
        AiToolCallEntity entity;
        synchronized (this) {
            int seq = ++toolSeq;
            Instant startedAt = Instant.now().minusMillis(durationMs == null ? 0 : durationMs);
            entity = new AiToolCallEntity(UUID.randomUUID(), id, seq, cut(name == null ? "" : name, 128),
                    cut(outcome, 16), startedAt);
            entity.setTurnSeq(lastTurnOfThread.get(Thread.currentThread()));
            entity.setDurationMs(durationMs);
            entity.setArgsChars(maskedArgs == null ? 0 : maskedArgs.length());
            entity.setResultChars(maskedResult == null ? 0 : maskedResult.length());
            entity.setArgsPreview(cut(maskedArgs, PREVIEW));
            entity.setResultPreview(cut(maskedResult, PREVIEW));
            entity.setArgsSha(put(maskedArgs));
            entity.setResultSha(put(maskedResult));
            sink.saveTool(entity);
            row.setToolCallCount(seq);
            row.setDurationMs(elapsedMs());
            sink.saveSession(row);
        }
        UUID callId = entity.getId();
        String counted = maskedSeen;
        sink.later(() -> {
            TraceFiles.Count count = countOf(put(counted), counted);
            if (count != null) {
                sink.toolTokens(callId, count.tokens(), count.exact());
            }
        });
    }

    /** Closes the session; a second call is ignored. */
    synchronized void finish(String error) {
        if (finished) {
            return;
        }
        finished = true;
        row.setStatus(error == null ? "DONE" : "FAILED");
        row.setErrorMessage(error);
        row.setFinishedAt(Instant.now());
        row.setDurationMs(elapsedMs());
        sink.saveSession(row);
        header();
    }

    /** Logs the first failure of this session's recording, and only the first. */
    synchronized void complain(Throwable failure) {
        if (!complained) {
            complained = true;
            System.out.println("[uptest] ai-trace " + shortId() + ": kayit yazilamadi ("
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + ") — AI cagrisi etkilenmedi");
        }
    }

    /* ------------------------------------------------------------------ */

    /** One message of a turn, as stored. */
    record Ref(String role, String name, String toolCallId, String sha, String text, boolean isNew) {
    }

    /**
     * Splits a turn's prompt by source and stores the split.
     *
     * Runs on the recorder's worker. Each distinct text is counted once per
     * session — turn thirty's prompt is twenty-nine turns of texts already
     * counted, plus the new ones.
     */
    void breakdown(UUID turnId, List<Ref> refs, Ref defs, Integer prompt) {
        int system = 0;
        int user = 0;
        int toolResults = 0;
        int assistant = 0;
        int toolDefs = 0;
        boolean exact = true;
        for (Ref ref : refs) {
            TraceFiles.Count count = countOf(ref.sha(), ref.text());
            if (count == null) {
                return;
            }
            exact &= count.exact();
            switch (ref.role()) {
                case "system" -> system += count.tokens();
                case "tool" -> toolResults += count.tokens();
                case "assistant" -> assistant += count.tokens();
                default -> user += count.tokens();
            }
        }
        if (defs != null) {
            TraceFiles.Count count = countOf(defs.sha(), defs.text());
            if (count == null) {
                return;
            }
            exact &= count.exact();
            toolDefs = count.tokens();
        }
        Integer overhead = prompt == null
                ? null
                : prompt - (system + user + toolResults + assistant + toolDefs);
        sink.breakdown(turnId, new AiTraceStore.Breakdown(system, toolDefs, user, toolResults,
                assistant, overhead, exact));
    }

    /**
     * A text's tokens: counted by the server when it answers, otherwise
     * estimated from this session's own characters-per-token; null when
     * neither is possible yet.
     */
    TraceFiles.Count countOf(String sha, String text) {
        if (text == null || text.isEmpty()) {
            return new TraceFiles.Count(0, true);
        }
        TraceFiles.Count known = sha == null ? null : counts.get(sha);
        if (known != null && known.exact()) {
            return known;
        }
        OptionalInt counted = sink.count(text);
        TraceFiles.Count count;
        if (counted.isPresent()) {
            count = new TraceFiles.Count(counted.getAsInt(), true);
        } else if (known != null) {
            return known;
        } else {
            Double ratio = charsPerToken;
            if (ratio == null || ratio <= 0) {
                return null;
            }
            count = new TraceFiles.Count((int) Math.round(text.length() / ratio), false);
        }
        if (sha != null) {
            counts.put(sha, count);
            try {
                sink.files().tokens(id, sha, count.tokens(), count.exact());
            } catch (IOException | RuntimeException unwritable) {
                complain(unwritable);
            }
        }
        return count;
    }

    private String maskFor(String role, String text) {
        return "tool".equals(role) || "assistant".equals(role) ? mask.maskValues(text) : mask.maskText(text);
    }

    private String put(String text) {
        try {
            return sink.files().put(id, text);
        } catch (IOException | RuntimeException unwritable) {
            complain(unwritable);
            return text == null ? null : TraceFiles.sha(text);
        }
    }

    private void writeTurn(int seq, TurnRecord call, List<Ref> refs, Ref defs, AiTurnEntity turn,
                           List<Map<String, Object>> calls) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("seq", seq);
        doc.put("startedAt", String.valueOf(call.startedAt()));
        doc.put("durationMs", call.durationMs());
        doc.put("model", call.model());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt", call.prompt());
        usage.put("completion", call.completion());
        usage.put("total", totalOf(call));
        usage.put("cached", call.cached());
        doc.put("usage", usage);
        doc.put("finishReason", call.finishReason());
        doc.put("vision", call.vision());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Ref ref : refs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", ref.role());
            entry.put("name", ref.name());
            entry.put("toolCallId", ref.toolCallId());
            entry.put("sha", ref.sha());
            entry.put("chars", ref.text() == null ? 0 : ref.text().length());
            entry.put("isNew", ref.isNew());
            messages.add(entry);
        }
        doc.put("messages", messages);
        if (defs != null) {
            doc.put("toolDefs", Map.of("sha", defs.sha(), "chars", defs.text().length(),
                    "count", call.toolDefCount()));
        }
        doc.put("response", Map.of("sha", String.valueOf(turn.getResponseSha()), "chars", turn.getResponseChars()));
        if (turn.getReasoningSha() != null) {
            doc.put("reasoning", Map.of("sha", turn.getReasoningSha(), "chars", turn.getReasoningChars()));
        }
        doc.put("toolCalls", calls);
        doc.put("error", call.error());
        try {
            sink.files().turn(id, seq, doc);
        } catch (IOException | RuntimeException unwritable) {
            complain(unwritable);
        }
    }

    private void header() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id.toString());
        doc.put("kind", row.getKind());
        doc.put("status", row.getStatus());
        doc.put("title", row.getTitle());
        doc.put("app", row.getApp());
        doc.put("product", row.getProduct());
        doc.put("flow", row.getFlow());
        doc.put("scenarioPath", row.getScenarioPath());
        doc.put("tapeId", row.getTapeId());
        doc.put("parentId", row.getParentId() == null ? null : row.getParentId().toString());
        doc.put("startedAt", String.valueOf(row.getStartedAt()));
        doc.put("finishedAt", row.getFinishedAt() == null ? null : row.getFinishedAt().toString());
        doc.put("models", row.getModels());
        doc.put("turns", row.getTurnCount());
        doc.put("toolCalls", row.getToolCallCount());
        doc.put("promptTokens", row.getPromptTokens());
        doc.put("completionTokens", row.getCompletionTokens());
        doc.put("totalTokens", row.getTotalTokens());
        doc.put("cachedTokens", row.getCachedTokens());
        doc.put("error", row.getErrorMessage());
        try {
            sink.files().header(id, doc);
        } catch (IOException | RuntimeException unwritable) {
            complain(unwritable);
        }
    }

    private static Integer totalOf(TurnRecord call) {
        if (call.total() != null && call.total() > 0) {
            return call.total();
        }
        if (call.prompt() == null && call.completion() == null) {
            return null;
        }
        return (call.prompt() == null ? 0 : call.prompt()) + (call.completion() == null ? 0 : call.completion());
    }

    private long elapsedMs() {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private String shortId() {
        return id.toString().substring(0, 8);
    }

    static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
