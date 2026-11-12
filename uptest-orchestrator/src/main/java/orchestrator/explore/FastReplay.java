package orchestrator.explore;

import orchestrator.explore.ReplayCalls.ReplayCall;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * "Hızlı çalıştır": a passed walk's tool calls, sent again in order, straight
 * from the database — the page is never read to FIND anything.
 *
 * <p>Each {@link ReplayCall} already carries a target that does not depend on
 * the page (the Playwright locator MCP ran when the walk was recorded), so a
 * call goes to the MCP tool as it is. There is no name lookup, no snapshot
 * before an action, no model. Between calls only the bank's loading overlay is
 * waited out ({@link BusyPage}), as on every walk.
 *
 * <p>The first call that errors stops the replay: nothing after it is sent,
 * because a replay that goes on past a missing button is no longer the
 * recorded walk. The caller then fails the run, drops the cache, and the
 * browser is closed like at the end of any walk.
 *
 * <p>The page IS read at the checkpoints only — right after a call whose step
 * has a "Beklenen:" under it, and at the end — so the one model turn can judge
 * the expectations exactly as "AI ile çalıştır" judges them: from the page.
 *
 * <p>A closed browser is not a broken cache: it ends the run as the walk's own
 * guard does ({@link BudgetedTool.BrowserClosed}) and the cache stays.
 */
final class FastReplay {

    /** A page kept for the verdict: after which call, what was expected there. */
    record Checkpoint(int step, String expected, String page) {
    }

    /**
     * @param log         one line per call, as printed
     * @param done        the calls that were carried out, as {@link ReplayCall#says}
     * @param stoppedAt   1-based call that failed, or 0 when all were sent
     * @param reason      why it stopped, or null
     * @param checkpoints the pages kept for the verdict turn
     * @param lastPage    the page at the end, raw; null when it stopped
     */
    record Result(List<String> log, List<String> done, int stoppedAt, String reason,
                  List<Checkpoint> checkpoints, String lastPage) {

        boolean complete() {
            return stoppedAt == 0;
        }
    }

    private static final String PREFIX = "[uptest] ai-cache ";

    private static final List<String> GONE = List.of("has been closed", "no open pages", "Page URL: about:blank");

    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();
    private final ToolCallback[] raw;
    private final ActionTape tape;
    private final Duration settle;

    /** @param raw the MCP callbacks as they come — no guard, no snapshot after each call */
    FastReplay(ToolCallback[] raw, ActionTape tape, Duration settle) {
        this.raw = raw;
        for (ToolCallback callback : raw) {
            tools.put(callback.getToolDefinition().name(), callback);
        }
        this.tape = tape;
        this.settle = settle;
    }

    Result run(List<ReplayCall> calls, LocalDate today, UnaryOperator<String> summary) {
        List<String> log = new ArrayList<>();
        List<String> done = new ArrayList<>();
        List<Checkpoint> checkpoints = new ArrayList<>();
        int total = calls.size();
        print(log, "hızlı çalıştır: " + total + " çağrı kayıttan, sayfa okunmadan");
        for (int i = 0; i < total; i++) {
            ReplayCall call = calls.get(i);
            String head = (i + 1) + "/" + total + " " + call.says()
                    + (call.target() == null ? "" : "  [" + call.target() + "]");
            BusyPage.waitWhileBusy(tools.get("browser_evaluate"), settle);
            String failure = send(call, today);
            if (failure != null) {
                print(log, head + " → TUTMADI: " + failure);
                return new Result(log, done, i + 1, failure, checkpoints, null);
            }
            print(log, head + " → tamam");
            done.add(call.says());
            if (call.expected() != null) {
                checkpoints.add(new Checkpoint(i + 1, call.expected(), summary.apply(look())));
                print(log, "   beklenen \"" + call.expected() + "\" → sayfa AI kararına saklandı");
            }
        }
        String last = look();
        print(log, "bitti: " + total + "/" + total + " çağrı gönderildi");
        return new Result(log, done, 0, null, checkpoints, last);
    }

    /** Sends one call; null when it went through, otherwise what went wrong. */
    private String send(ReplayCall call, LocalDate today) {
        long began = System.nanoTime();
        String args = call.argsFor(today);
        String result;
        try {
            if ("set_date".equals(call.tool())) {
                DateTool dates = DateTool.bind(raw);
                if (dates == null) {
                    return "set_date bu koşuda yok";
                }
                result = dates.setDate(call.target(), call.dateFor(today));
            } else {
                ToolCallback tool = tools.get(call.tool());
                if (tool == null) {
                    return call.tool() + " bu koşuda yok";
                }
                result = tool.call(args);
            }
        } catch (RuntimeException failed) {
            result = "### Error\n" + failed.getMessage();
        }
        gone(result);
        boolean ok = result != null && !result.contains("### Error")
                && (!"set_date".equals(call.tool()) || result.startsWith("Set to"));
        tape.record(call.tool(), args, ok ? ActionTape.Outcome.OK : ActionTape.Outcome.ERROR, result,
                Duration.ofNanos(System.nanoTime() - began).toMillis());
        return ok ? null : firstLine(result);
    }

    /** The page at a checkpoint, recorded on the tape like any snapshot. */
    private String look() {
        ToolCallback snapshot = tools.get("browser_snapshot");
        if (snapshot == null) {
            return null;
        }
        BusyPage.waitWhileBusy(tools.get("browser_evaluate"), settle);
        long began = System.nanoTime();
        try {
            String page = snapshot.call("{}");
            gone(page);
            tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, page,
                    Duration.ofNanos(System.nanoTime() - began).toMillis());
            return page;
        } catch (BudgetedTool.BrowserClosed closed) {
            throw closed;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The operator closed the window: not the cache's fault, so not a failed call. */
    private static void gone(String result) {
        if (result == null) {
            return;
        }
        String plain = LeanResults.unwrap(result);
        for (String marker : GONE) {
            if (plain.contains(marker)) {
                System.out.println(PREFIX + "tarayici kapandi (" + marker + ") — kosu sonlandiriliyor, akis yerinde");
                throw new BudgetedTool.BrowserClosed();
            }
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * The one model turn of a fast run: every call was sent; the model only
     * holds the expectations against the pages kept, as the agent would.
     */
    static String verdictTurn(String scenario, Result walk, List<String> unmatched, String lastPage) {
        StringBuilder out = new StringBuilder();
        out.append("Bu senaryonun adımlarının HEPSİ önceki başarılı koşudan kaydedilen araç çağrılarıyla KODLA")
                .append(" yapıldı. Senin işin yalnızca sonucu değerlendirmek; hiçbir şeye tıklama.\n\n")
                .append(scenario.strip()).append("\n\nYapılan adımlar:\n");
        walk.log().forEach(line -> out.append(line).append('\n'));
        for (Checkpoint point : walk.checkpoints()) {
            out.append("\n").append(point.step()).append(". adımdan HEMEN SONRAKİ ekran. Beklenen: ")
                    .append(point.expected()).append('\n').append(screen(point.page()));
        }
        if (!unmatched.isEmpty()) {
            out.append("\nAdımı eşleştirilemeyen beklentiler (son ekranda değerlendir):\n");
            unmatched.forEach(line -> out.append("- ").append(line).append('\n'));
        }
        out.append("\nSon ekran:\n").append(screen(lastPage));
        return out.toString();
    }

    private static String screen(String page) {
        return CompactingAdvisor.SCREEN_OPEN + "\n" + (page == null ? "(okunamadı)" : page) + "\n"
                + CompactingAdvisor.SCREEN_CLOSE + "\n";
    }

    private static void print(List<String> log, String line) {
        log.add(line);
        System.out.println(PREFIX + line);
    }

    private static String firstLine(String text) {
        String plain = text == null ? "" : LeanResults.unwrap(text).replace("### Error", "").strip();
        String first = plain.lines().filter(line -> !line.isBlank()).findFirst().orElse("bilinmeyen hata").strip();
        if (first.startsWith("Error: ")) {
            first = first.substring(7);
        }
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }
}
