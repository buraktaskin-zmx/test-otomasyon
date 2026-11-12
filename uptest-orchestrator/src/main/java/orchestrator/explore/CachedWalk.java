package orchestrator.explore;

import orchestrator.explore.CachedSteps.CachedStep;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Carries out a scenario's cached steps in code, with no model, one at a time.
 *
 * <p>Every step goes through {@link StepRunner} bound to the request's
 * WRAPPED tools, so the cached walk is guarded exactly as the agent is: the
 * unasked-confirmation guard, the off-limits list, the closed browser, the
 * page after every action, the tape. ClaimCheck and the Gherkin renderer read
 * a cached walk the same way they read the agent's.
 *
 * <p>An expectation written under a step ({@link StepExpectations}) is
 * checked right after that step, while its page is still there:
 * <ul>
 *   <li>quoted names → looked for on the page in code; no tokens;</li>
 *   <li>no quoted name → the page is kept for the one model turn that
 *       judges the run ({@link #verdictTurn}).</li>
 * </ul>
 *
 * <p>It stops at the first step that does not take, and at the first quoted
 * expectation the page does not show. Either way the agent takes over from
 * that page ({@link #takeoverTurn}); nothing is guessed in between. Every step
 * and every check is printed with the {@code [uptest] ai-cache} prefix.
 */
final class CachedWalk {

    /** A page kept for the verdict turn: after which step, what was expected there. */
    record Checkpoint(int step, String expected, String page) {
    }

    /**
     * @param log        one line per step and per check, as printed
     * @param stoppedAt  1-based step that stopped the walk, or 0 when all were done
     * @param reason     why it stopped, or null
     * @param checkpoints expectations left for the verdict turn, with their pages
     * @param noticed    what the screen started saying along the way (dialogs, "… giriniz.")
     * @param lastPage   the page as the walk left it, raw
     */
    record Result(List<String> log, int stoppedAt, String reason, List<Checkpoint> checkpoints,
                  List<String> noticed, String lastPage) {

        boolean complete() {
            return stoppedAt == 0;
        }
    }

    private static final String PREFIX = "[uptest] ai-cache ";

    private final StepRunner runner;

    CachedWalk(StepRunner runner) {
        this.runner = runner;
    }

    Result run(List<CachedStep> steps, LocalDate today, PageSummary summary) {
        List<String> log = new ArrayList<>();
        List<Checkpoint> checkpoints = new ArrayList<>();
        List<String> noticed = new ArrayList<>();
        int total = steps.size();
        print(log, "oynatılıyor: " + total + " adım, model YOK");
        for (int i = 0; i < total; i++) {
            CachedStep cached = steps.get(i);
            StepRunner.Step step = cached.asStep(today);
            Set<String> before = StepRunner.complaints(AriaSnapshot.of(runner.latestPage()));
            String outcome = runner.attemptNoted(step);
            String head = (i + 1) + "/" + total + " " + CachedSteps.describe(cached)
                    + (cached.dayOffset() != null ? " → " + step.value() : "");
            if (!outcome.startsWith("tamam")) {
                print(log, head + " → TUTMADI: " + outcome);
                return new Result(log, i + 1, outcome, checkpoints, noticed, runner.latestPage());
            }
            print(log, head + " → " + outcome);
            Set<String> now = StepRunner.complaints(AriaSnapshot.of(runner.latestPage()));
            now.removeAll(before);
            if (!now.isEmpty()) {
                String said = String.join(" | ", now);
                noticed.add((i + 1) + ". adımdan sonra: " + said);
                print(log, "   ekran yeni bir şey söylüyor: " + said);
            }
            if (cached.expected() == null) {
                continue;
            }
            List<String> names = StepExpectations.quotedIn(cached.expected());
            if (names.isEmpty()) {
                checkpoints.add(new Checkpoint(i + 1, cached.expected(), summary.of(runner.latestPage())));
                print(log, "   beklenen \"" + cached.expected() + "\" → sayfa AI kararına saklandı");
                continue;
            }
            List<String> missing = missingOn(runner.latestPage(), names);
            if (!missing.isEmpty()) {
                String why = "beklenen görülmedi: \"" + String.join("\", \"", missing) + "\" sayfada yok ("
                        + cached.expected() + ")";
                print(log, "   " + why + " → ajan devralacak");
                return new Result(log, i + 1, why, checkpoints, noticed, runner.latestPage());
            }
            print(log, "   beklenen \"" + cached.expected() + "\" → görüldü (kodla, 0 token)");
        }
        print(log, "bitti: " + total + "/" + total + " adım koddan yapıldı");
        return new Result(log, 0, null, checkpoints, noticed, runner.latestPage());
    }

    /** The quoted names the page does not show; compared folded, spaces ignored (a {@code <br>} label). */
    static List<String> missingOn(String page, List<String> names) {
        String text = ClaimCheck.compact(LeanResults.unwrap(page == null ? "" : page));
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            String needle = ClaimCheck.compact(name);
            if (!needle.isEmpty() && !text.contains(needle)) {
                missing.add(name);
            }
        }
        return missing;
    }

    /* ------------------------------------------------------------------ */

    /**
     * The one model turn of a cached run: the steps are done, the model only
     * holds the expectations against the pages it is shown.
     */
    static String verdictTurn(String scenario, Result walk, List<String> unmatched, String lastPage) {
        StringBuilder out = new StringBuilder();
        out.append("Bu senaryonun adımlarının HEPSİ önceki başarılı koşudan kaydedilen akışla KODLA yapıldı.")
                .append(" Senin işin yalnızca sonucu değerlendirmek; hiçbir şeye tıklama.\n\n")
                .append(scenario.strip()).append("\n\nYapılan adımlar:\n");
        walk.log().forEach(line -> out.append(line).append('\n'));
        if (!walk.noticed().isEmpty()) {
            out.append("\nEkranın yol boyunca söyledikleri:\n");
            walk.noticed().forEach(line -> out.append("- ").append(line).append('\n'));
        }
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

    /** What the agent is told when the cached walk stops: what was done, and to go on from here. */
    static String takeoverNote(Result walk) {
        StringBuilder out = new StringBuilder();
        out.append("Bu senaryonun ilk adımları önceki başarılı koşudan kaydedilen akışla KODLA yapıldı:\n");
        walk.log().forEach(line -> out.append(line).append('\n'));
        out.append("\nKayıtlı akış ").append(walk.stoppedAt()).append(". adımda durdu: ").append(walk.reason())
                .append("\nYapılmış adımları TEKRARLAMA. Ekran şu an aşağıdaki gibi; senaryoya buradan devam et.\n");
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

    /** Turns a raw page into what the model is shown: frame cut, summarised, capped. */
    @FunctionalInterface
    interface PageSummary {
        String of(String rawPage);
    }
}
