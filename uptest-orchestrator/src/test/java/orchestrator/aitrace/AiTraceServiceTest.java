package orchestrator.aitrace;

import orchestrator.persistence.AiSessionEntity;
import orchestrator.persistence.AiToolCallEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiTraceServiceTest {

    private static AiSessionEntity session(Instant at, long prompt, long completion) {
        AiSessionEntity s = new AiSessionEntity(UUID.randomUUID(), "ai-run", at);
        s.setPromptTokens(prompt);
        s.setCompletionTokens(completion);
        s.setTotalTokens(prompt + completion);
        return s;
    }

    @Test
    @DisplayName("a call at 01:00 Istanbul time belongs to that day, not to the previous UTC day")
    void daysAreCutInIstanbulTime() {
        LocalDate today = LocalDate.now(AiTraceService.ZONE);
        Instant oneAm = ZonedDateTime.of(today.atTime(1, 0), AiTraceService.ZONE).toInstant();
        List<AiTraceService.Day> days = AiTraceService.daily(
                List.of(session(oneAm, 100, 10)), oneAm.minusSeconds(3 * 86400), false);

        AiTraceService.Day last = days.get(days.size() - 1);
        assertThat(last.date()).isEqualTo(today.toString());
        assertThat(last.prompt()).isEqualTo(100);
        assertThat(last.completion()).isEqualTo(10);
        assertThat(last.sessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty days are bars of zero, so the axis is time; 'all' starts at the first session")
    void emptyDaysAreKeptAndAllStartsAtFirstSession() {
        Instant now = Instant.now();
        List<AiTraceService.Day> week = AiTraceService.daily(List.of(), now.minusSeconds(7 * 86400), false);
        assertThat(week).hasSize(8);
        assertThat(week).allMatch(day -> day.prompt() == 0 && day.sessions() == 0);

        List<AiTraceService.Day> all = AiTraceService.daily(
                List.of(session(now.minusSeconds(2 * 86400), 1, 1)), now.minusSeconds(3650L * 86400), true);
        assertThat(all).hasSizeBetween(3, 4);
    }

    @Test
    @DisplayName("per tool: calls, refusals and errors; result tokens stay unknown while any call is uncounted")
    void toolStatsSumPerTool() {
        AiToolCallEntity a = call("browser_snapshot", "OK", 9000, 2400);
        AiToolCallEntity b = call("browser_snapshot", "OK", 7000, null);
        AiToolCallEntity c = call("browser_click", "REFUSED", 40, 10);
        List<AiTraceService.ToolStat> stats = AiTraceService.toolStats(List.of(a, b, c));

        assertThat(stats.get(0).tool()).isEqualTo("browser_snapshot");
        assertThat(stats.get(0).calls()).isEqualTo(2);
        assertThat(stats.get(0).resultChars()).isEqualTo(16000);
        assertThat(stats.get(0).resultTokens()).isNull();
        assertThat(stats.get(1).refused()).isEqualTo(1);
        assertThat(stats.get(1).resultTokens()).isEqualTo(10);
    }

    @Test
    void kindsParseAsACommaList() {
        assertThat(AiTraceService.kindsOf("flow-read, flow-plan,flow-suggest"))
                .containsExactlyInAnyOrder("flow-read", "flow-plan", "flow-suggest");
        assertThat(AiTraceService.kindsOf(" ")).isEmpty();
        assertThat(AiTraceService.periodName("bogus")).isEqualTo("7d");
    }

    @Test
    void tokenizeRootDropsTheV1() {
        assertThat(TokenCounter.rootOf("https://vllm.example/v1")).isEqualTo("https://vllm.example");
        assertThat(TokenCounter.rootOf("https://vllm.example/v1/")).isEqualTo("https://vllm.example");
        assertThat(TokenCounter.rootOf("")).isNull();
    }

    private static AiToolCallEntity call(String tool, String outcome, int chars, Integer tokens) {
        AiToolCallEntity call = new AiToolCallEntity(UUID.randomUUID(), UUID.randomUUID(), 1, tool, outcome, Instant.now());
        call.setResultChars(chars);
        call.setResultTokens(tokens);
        call.setDurationMs(5L);
        return call;
    }
}
