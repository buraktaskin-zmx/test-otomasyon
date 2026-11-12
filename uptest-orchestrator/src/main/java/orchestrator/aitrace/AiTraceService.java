package orchestrator.aitrace;

import orchestrator.persistence.AiSessionEntity;
import orchestrator.persistence.AiToolCallEntity;
import orchestrator.persistence.AiTurnEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What the Token Takibi panel reads; see docs/token-takibi/README.md for what each
 * number means.
 *
 * Sums are made here, not in the browser, for the dashboard's reason: the
 * page asks a question, the server answers it.
 */
@Service
public class AiTraceService {

    /** Days are cut in Istanbul time, as on the dashboard — a 01:00 call belongs to today. */
    static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    /** The newest sessions listed; older ones still count in the totals. */
    static final int LIST_LIMIT = 300;

    /**
     * How much of each message is sent with the list of a turn's messages.
     *
     * Enough to recognise a text without opening it: a list of a dozen empty
     * "Göster" buttons is what the message view looked like before, and
     * recognising the system prompt should not cost a click and a request.
     */
    static final int MESSAGE_PREVIEW = 400;

    private final AiTraceStore store;
    private final AiTraceRecorder recorder;

    /** "AI ile çalıştır"'s token target (uptest.explore.ai-run.token-budget); 0 = none. */
    private final long aiRunBudget;

    public AiTraceService(AiTraceStore store, AiTraceRecorder recorder,
                          @Value("${uptest.explore.ai-run.token-budget:100000}") long aiRunBudget) {
        this.store = store;
        this.recorder = recorder;
        this.aiRunBudget = Math.max(0, aiRunBudget);
    }

    /* ----------------------------- overview ----------------------------- */

    public Overview overview(String period, String kind) {
        String name = periodName(period);
        Instant since = Instant.now().minus(lengthOf(name));
        Set<String> kinds = kindsOf(kind);
        List<AiSessionEntity> all = store.sessionsSince(since).stream()
                .filter(session -> kinds.isEmpty()
                        ? !"health".equals(session.getKind())
                        : kinds.contains(session.getKind()))
                .toList();

        long prompt = 0;
        long completion = 0;
        long total = 0;
        Long cached = null;
        long aiRunTokens = 0;
        int aiRuns = 0;
        AiSessionEntity priciest = null;
        for (AiSessionEntity session : all) {
            prompt += session.getPromptTokens();
            completion += session.getCompletionTokens();
            total += session.getTotalTokens();
            if (session.getCachedTokens() != null) {
                cached = (cached == null ? 0 : cached) + session.getCachedTokens();
            }
            if ("ai-run".equals(session.getKind()) && !"RUNNING".equals(session.getStatus())) {
                aiRuns++;
                aiRunTokens += session.getTotalTokens();
            }
            if (priciest == null || session.getTotalTokens() > priciest.getTotalTokens()) {
                priciest = session;
            }
        }
        Kpis kpis = new Kpis(total, prompt, completion, cached, all.size(),
                aiRuns == 0 ? null : aiRunTokens / aiRuns, aiRuns,
                priciest == null || priciest.getTotalTokens() == 0 ? null : row(priciest));

        Map<String, KindRow> byKind = new LinkedHashMap<>();
        for (AiSessionEntity session : all) {
            byKind.merge(session.getKind(), new KindRow(session.getKind(), 1, session.getTotalTokens()),
                    (a, b) -> new KindRow(a.kind(), a.sessions() + b.sessions(), a.tokens() + b.tokens()));
        }
        List<KindRow> kindRows = byKind.values().stream()
                .sorted(Comparator.comparingLong(KindRow::tokens).reversed())
                .toList();

        List<SessionRow> rows = all.stream().limit(LIST_LIMIT).map(AiTraceService::row).toList();
        boolean running = all.stream().anyMatch(session -> "RUNNING".equals(session.getStatus()));
        return new Overview(name, kind == null ? "" : kind, kpis, daily(all, since, "all".equals(name)), kindRows, rows,
                running, all.size() > LIST_LIMIT, aiRunBudget);
    }

    /**
     * One bar per day from the start of the window, empty days included, so
     * the axis is time.
     *
     * @param fromFirstSession "tümü": its window starts ten years back, so the
     *                         chart starts at the first session instead. Only
     *                         then — a quiet week is still seven bars.
     */
    static List<Day> daily(List<AiSessionEntity> sessions, Instant since, boolean fromFirstSession) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate first = since.atZone(ZONE).toLocalDate();
        if (fromFirstSession) {
            first = sessions.stream()
                    .map(session -> session.getStartedAt().atZone(ZONE).toLocalDate())
                    .min(Comparator.naturalOrder())
                    .orElse(today);
        }
        Map<LocalDate, long[]> sums = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            sums.put(day, new long[3]);
        }
        for (AiSessionEntity session : sessions) {
            long[] sum = sums.get(session.getStartedAt().atZone(ZONE).toLocalDate());
            if (sum != null) {
                sum[0] += session.getPromptTokens();
                sum[1] += session.getCompletionTokens();
                sum[2]++;
            }
        }
        List<Day> out = new ArrayList<>();
        sums.forEach((day, sum) -> out.add(new Day(day.toString(), sum[0], sum[1], (int) sum[2])));
        return out;
    }

    /* ------------------------------ detail ------------------------------ */

    public Optional<Detail> detail(UUID id) {
        Optional<AiSessionEntity> found = store.session(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AiSessionEntity session = found.get();
        List<TurnRow> turns = store.turns(id).stream().map(AiTraceService::turnRow).toList();
        List<AiToolCallEntity> calls = store.toolCalls(id);
        List<ToolRow> tools = calls.stream().map(AiTraceService::toolRow).toList();
        SessionRow parent = session.getParentId() == null ? null
                : store.session(session.getParentId()).map(AiTraceService::row).orElse(null);
        List<SessionRow> children = store.children(id).stream().map(AiTraceService::row).toList();
        return Optional.of(new Detail(row(session), parent, turns, tools, toolStats(calls),
                totals(turns), children,
                "ai-run".equals(session.getKind()) && aiRunBudget > 0 ? aiRunBudget : null));
    }

    /** Per tool: how often, how it went, what its results cost. */
    static List<ToolStat> toolStats(List<AiToolCallEntity> calls) {
        Map<String, ToolStat> stats = new LinkedHashMap<>();
        for (AiToolCallEntity call : calls) {
            ToolStat one = new ToolStat(call.getTool(), 1,
                    "REFUSED".equals(call.getOutcome()) ? 1 : 0,
                    "ERROR".equals(call.getOutcome()) ? 1 : 0,
                    call.getResultChars(),
                    call.getResultTokens() == null ? null : call.getResultTokens().longValue(),
                    call.getDurationMs() == null ? 0 : call.getDurationMs());
            stats.merge(call.getTool(), one, (a, b) -> new ToolStat(a.tool(), a.calls() + b.calls(),
                    a.refused() + b.refused(), a.errors() + b.errors(), a.resultChars() + b.resultChars(),
                    a.resultTokens() == null || b.resultTokens() == null ? null : a.resultTokens() + b.resultTokens(),
                    a.durationMs() + b.durationMs()));
        }
        return stats.values().stream()
                .sorted(Comparator.comparingLong(ToolStat::resultChars).reversed())
                .toList();
    }

    /** The prompt split summed over every turn that has one; null when none has. */
    static BreakdownRow totals(List<TurnRow> turns) {
        long[] sum = new long[6];
        boolean any = false;
        boolean exact = true;
        int missing = 0;
        for (TurnRow turn : turns) {
            BreakdownRow split = turn.breakdown();
            if (split == null) {
                missing++;
                continue;
            }
            any = true;
            exact &= Boolean.TRUE.equals(split.exact());
            sum[0] += nz(split.system());
            sum[1] += nz(split.toolDefs());
            sum[2] += nz(split.user());
            sum[3] += nz(split.toolResults());
            sum[4] += nz(split.assistant());
            sum[5] += nz(split.overhead());
        }
        if (!any) {
            return null;
        }
        return new BreakdownRow(sum[0], sum[1], sum[2], sum[3], sum[4], sum[5], exact, missing);
    }

    /* ----------------------------- messages ----------------------------- */

    /** One turn's whole prompt and answer, by reference; the texts are fetched one by one. */
    @SuppressWarnings("unchecked")
    public Optional<TurnMessages> messages(UUID id, int seq) {
        TraceFiles files = recorder.files();
        return files.turn(id, seq).map(doc -> {
            List<MessageRow> messages = new ArrayList<>();
            Object listed = doc.get("messages");
            if (listed instanceof List<?> list) {
                int index = 0;
                for (Object item : list) {
                    if (item instanceof Map<?, ?> entry) {
                        messages.add(message(files, id, index++, (Map<String, Object>) entry));
                    }
                }
            }
            MessageRow defs = part(files, id, "tool_defs", doc.get("toolDefs"));
            MessageRow response = part(files, id, "response", doc.get("response"));
            MessageRow reasoning = part(files, id, "reasoning", doc.get("reasoning"));
            Object calls = doc.get("toolCalls");
            return new TurnMessages(seq, messages, defs, response, reasoning,
                    calls instanceof List<?> list ? (List<Object>) list : List.of());
        });
    }

    /** The same turn as ONE text, in the order the model read it; see {@link TurnTranscript}. */
    public Optional<String> transcript(UUID id, int seq) {
        return TurnTranscript.of(recorder.files(), id, seq);
    }

    public Optional<String> content(UUID id, String sha) {
        return recorder.files().read(id, sha);
    }

    private static MessageRow message(TraceFiles files, UUID id, int index, Map<String, Object> entry) {
        String sha = str(entry.get("sha"));
        Optional<TraceFiles.Count> count = files.count(id, sha);
        return new MessageRow(index, str(entry.get("role")), str(entry.get("name")),
                str(entry.get("toolCallId")), sha, num(entry.get("chars")),
                Boolean.TRUE.equals(entry.get("isNew")),
                count.map(TraceFiles.Count::tokens).orElse(null),
                count.map(TraceFiles.Count::exact).orElse(null),
                files.preview(id, sha, MESSAGE_PREVIEW).orElse(null));
    }

    @SuppressWarnings("unchecked")
    private static MessageRow part(TraceFiles files, UUID id, String role, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> entry = (Map<String, Object>) map;
        String sha = str(entry.get("sha"));
        if (!TraceFiles.isSha(sha)) {
            return null;
        }
        Optional<TraceFiles.Count> count = files.count(id, sha);
        return new MessageRow(-1, role, null, null, sha, num(entry.get("chars")), false,
                count.map(TraceFiles.Count::tokens).orElse(null),
                count.map(TraceFiles.Count::exact).orElse(null),
                files.preview(id, sha, MESSAGE_PREVIEW).orElse(null));
    }

    /* ------------------------------ mapping ----------------------------- */

    static SessionRow row(AiSessionEntity s) {
        return new SessionRow(s.getId().toString(), s.getKind(), s.getStatus(), s.getStartedAt(),
                s.getFinishedAt(), s.getDurationMs(), s.getTitle(), s.getApp(), s.getProduct(), s.getFlow(),
                s.getScenarioPath(), s.getCif(), s.getTapeId(),
                s.getParentId() == null ? null : s.getParentId().toString(), s.getModels(),
                s.getPromptTokens(), s.getCompletionTokens(), s.getTotalTokens(), s.getCachedTokens(),
                s.getTurnCount(), s.getToolCallCount(), s.getErrorMessage());
    }

    static TurnRow turnRow(AiTurnEntity t) {
        BreakdownRow split = t.getBreakdownExact() == null ? null
                : new BreakdownRow(lng(t.getTokSystem()), lng(t.getTokToolDefs()), lng(t.getTokUser()),
                        lng(t.getTokToolResults()), lng(t.getTokAssistant()), lng(t.getTokOverhead()),
                        t.getBreakdownExact(), 0);
        return new TurnRow(t.getSeq(), t.getStartedAt(), t.getDurationMs(), t.getModel(), t.getPromptTokens(),
                t.getCompletionTokens(), t.getTotalTokens(), t.getCachedTokens(), t.getFinishReason(),
                t.getMessageCount(), t.getNewMessageCount(), t.getToolDefCount(), t.getRequestedToolCalls(),
                t.getResponseChars(), t.getResponsePreview(), t.getResponseSha(), t.getReasoningChars(),
                t.getReasoningSha(), split, t.getErrorMessage());
    }

    static ToolRow toolRow(AiToolCallEntity c) {
        return new ToolRow(c.getSeq(), c.getTurnSeq(), c.getTool(), c.getOutcome(), c.getStartedAt(),
                c.getDurationMs(), c.getArgsChars(), c.getResultChars(), c.getResultTokens(), c.getResultExact(),
                c.getArgsPreview(), c.getResultPreview(), c.getArgsSha(), c.getResultSha());
    }

    static String periodName(String period) {
        String key = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "24h", "30d", "all" -> key;
            default -> "7d";
        };
    }

    static Duration lengthOf(String name) {
        return switch (name) {
            case "24h" -> Duration.ofHours(24);
            case "30d" -> Duration.ofDays(30);
            case "all" -> Duration.ofDays(3650);
            default -> Duration.ofDays(7);
        };
    }

    /** Comma separated kinds; blank = everything but the health check. */
    static Set<String> kindsOf(String kind) {
        if (kind == null || kind.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(kind.split(",")).map(String::trim).filter(part -> !part.isEmpty())
                .collect(Collectors.toSet());
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static Long lng(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int num(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /* ------------------------------ shapes ------------------------------ */

    public record SessionRow(String id, String kind, String status, Instant startedAt, Instant finishedAt,
                             Long durationMs, String title, String app, String product, String flow,
                             String scenarioPath, String cif, String tapeId, String parentId, String models,
                             long promptTokens, long completionTokens, long totalTokens, Long cachedTokens,
                             int turns, int toolCalls, String error) {
    }

    public record Kpis(long totalTokens, long promptTokens, long completionTokens, Long cachedTokens,
                       int sessions, Long avgPerAiRun, int aiRuns, SessionRow mostExpensive) {
    }

    public record Day(String date, long prompt, long completion, int sessions) {
    }

    public record KindRow(String kind, int sessions, long tokens) {
    }

    /** {@code aiRunBudget}: "AI ile çalıştır"'s token target; 0 when none is set. */
    public record Overview(String period, String kind, Kpis kpis, List<Day> daily, List<KindRow> byKind,
                           List<SessionRow> sessions, boolean running, boolean truncated, long aiRunBudget) {
    }

    /** {@code missingTurns}: turns not yet (or never) split — counted in no part. */
    public record BreakdownRow(Long system, Long toolDefs, Long user, Long toolResults, Long assistant,
                               Long overhead, Boolean exact, int missingTurns) {
    }

    public record TurnRow(int seq, Instant startedAt, long durationMs, String model, Integer promptTokens,
                          Integer completionTokens, Integer totalTokens, Long cachedTokens, String finishReason,
                          int messageCount, int newMessageCount, int toolDefCount, int requestedToolCalls,
                          int responseChars, String responsePreview, String responseSha, int reasoningChars,
                          String reasoningSha, BreakdownRow breakdown, String error) {
    }

    public record ToolRow(int seq, Integer turnSeq, String tool, String outcome, Instant startedAt,
                          Long durationMs, int argsChars, int resultChars, Integer resultTokens,
                          Boolean resultExact, String argsPreview, String resultPreview, String argsSha,
                          String resultSha) {
    }

    public record ToolStat(String tool, int calls, int refused, int errors, long resultChars,
                           Long resultTokens, long durationMs) {
    }

    /** {@code budget}: the token target this session was run against; null for other kinds. */
    public record Detail(SessionRow session, SessionRow parent, List<TurnRow> turns, List<ToolRow> toolCalls,
                         List<ToolStat> toolStats, BreakdownRow totals, List<SessionRow> children,
                         Long budget) {
    }

    /** {@code preview}: the first {@link #MESSAGE_PREVIEW} characters, so the list reads without a click. */
    public record MessageRow(int index, String role, String name, String toolCallId, String sha, int chars,
                             boolean isNew, Integer tokens, Boolean exact, String preview) {
    }

    public record TurnMessages(int seq, List<MessageRow> messages, MessageRow toolDefs, MessageRow response,
                               MessageRow reasoning, List<Object> toolCalls) {
    }
}
