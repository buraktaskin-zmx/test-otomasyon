package orchestrator.dashboard;

import orchestrator.catalog.CatalogService;
import orchestrator.persistence.TestRunRepository;
import orchestrator.persistence.TestRunRepository.RunSummary;
import orchestrator.persistence.TestRunStepEntity;
import orchestrator.persistence.TestRunStepRepository;
import orchestrator.product.ProductModel;
import orchestrator.product.ProductService;
import orchestrator.product.ScenarioCard;
import orchestrator.product.ScenarioStore;
import orchestrator.users.TestUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The numbers behind the dashboard, as docs/DASHBOARD.md defines them — stage 1:
 * from what the database holds today.
 *
 * <p><b>Runs are matched to scenarios by id, and by NAME only where there is
 * none.</b> A run started from Kayıtlı Senaryolar records its scenario's id
 * (stage 2); older runs, and text run from the editor unsaved, carry only a
 * title. Those count for a scenario when exactly one scenario carries that
 * title on that screen, and any figure that rests on such a match is sent
 * with {@code approximate = true} so the page can mark it "≈". The startup
 * backfill ({@link #backfill}) turns today's title matches into ids, so a
 * later rename does not cut an old run loose.
 *
 * <p>Read-only, and computed per request: a month of runs is a few thousand
 * short rows, and the feature text and status JSON are never read.
 */
@Service
public class DashboardService {

    /** Days are cut where the analysts' day is, not at UTC midnight. */
    static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    /** The UI pages through these; a card never grows with the list. */
    private static final int RECENT_RUNS = 60;
    private static final int TOP_STEPS = 25;

    private final TestRunRepository runs;
    private final TestRunStepRepository steps;
    private final ProductService products;
    private final ScenarioStore store;
    private final CatalogService catalogs;
    private final TestUserService users;

    public DashboardService(TestRunRepository runs, TestRunStepRepository steps,
                            ProductService products, ScenarioStore store, CatalogService catalogs,
                            TestUserService users) {
        this.runs = runs;
        this.steps = steps;
        this.products = products;
        this.store = store;
        this.catalogs = catalogs;
        this.users = users;
    }

    /* ------------------------------ shapes ------------------------------ */

    /**
     * The period's numbers.
     *
     * <p><b>{@code runs}/{@code runsPassed}/{@code runsFailed} count RUNS, not
     * scenarios</b>, and that is the whole point of them. The older
     * {@code ran}/{@code neverRun} pair asks "how much of the library has been
     * touched" and answers it per SCENARIO, by that scenario's LAST run — so
     * running one scenario four times leaves it at 1, which read as the
     * dashboard not recording anything. Both questions are real and they are
     * not the same question: coverage stays in the first card's sub-line,
     * while the cards themselves now count what was actually executed and go
     * up every single run.
     *
     * <p>Nothing here is derived from a cache. Every run lives in H2's
     * {@code TEST_RUN} for good — there is no retention job and nothing
     * prunes it (the 200-run cap is the EXECUTOR's in-memory pool, a
     * different thing entirely). A number only "disappears" because the
     * selected period excludes it, which is why there is a "tümü" period.
     */
    public record Kpis(int scenarios, int runnable, int notRunnable, int ran, int neverRun,
                       int runs, int runsPassed, int runsFailed, int failedSteps,
                       Long averageDurationMs, Previous previous, boolean approximate) {
    }

    /** The same length just before the window, for the deltas — also in runs. */
    public record Previous(int runs, int passed, int failed) {
    }

    public record Day(String date, int passed, int failed, int canceled, int unknown, int running) {
    }

    public record RecentRun(String runId, String scenario, String path, String screen, String flow,
                            String status, int passed, int total, long durationMs,
                            String submittedAt, String executor, String cif, String trigger) {
    }

    /** One CIF's runs in the period; the name comes from testdata/users.yaml. */
    public record CifRow(String cif, String name, int runs, int passed, int failed,
                         String lastRunAt) {
    }

    /** A scenario that both passed and failed in the period. */
    public record FlakyRow(String app, String product, String path, String title, String screen,
                           int passed, int failed, String lastFailedStep) {
    }

    /** One run of one scenario, for its history page. */
    public record HistoryRun(String runId, String submittedAt, String status, String cif,
                             String trigger, String batchId, String executor, long durationMs,
                             int passed, int total, String failedStep, String failedError) {
    }

    public record History(String app, String product, String path, String title, String screen,
                          String heading, List<String> rules, List<HistoryRun> runs) {
    }

    public record FailingStep(String step, int runs, int scenarios, String lastError) {
    }

    public record Failing(List<FailingStep> steps, int loginFailures) {
    }

    public record ScreenBar(String app, String product, String productName, String flow, String name,
                            int total, int passed, int failed, int neverRun, int notRunnable) {
    }

    public record Overview(String period, String cif, Kpis kpis, List<Day> daily,
                           List<RecentRun> recentRuns, Failing failing, List<ScreenBar> screens,
                           List<CifRow> cifs, List<FlakyRow> flaky) {
    }

    public record HeadingBar(String name, int total, int runnable, int notRunnable,
                             int passed, int failed, int neverRun) {
    }

    /** {@code last10}: the newest ten finished-or-stopped runs' statuses, oldest first. */
    public record ScenarioRow(String path, String title, String heading, boolean runnable,
                              String lastStatus, String lastRunAt, int passed, int total,
                              Long durationMs, int ruleCount, boolean stale, List<String> risks,
                              String lastCif, List<String> last10) {
    }

    public record ScreenDetail(String period, String app, String product, String productName,
                               String flow, String name, String catalog, int ruleCount, Kpis kpis,
                               List<HeadingBar> headings, Failing failing, List<ScenarioRow> scenarios) {
    }

    /* ----------------------------- requests ----------------------------- */

    /** @param cif blank for every CIF */
    @Transactional(readOnly = true)
    public Overview overview(String period, String cif) {
        Window window = Window.of(period);
        Data data = load(window, cif);
        List<Scenario> all = data.scenarios;
        // Every run in the window counts here, including one that never
        // matched a saved scenario: it was still executed, and a run the
        // dashboard refuses to count is exactly what made these cards look
        // frozen at 1.
        return new Overview(window.name, blankToNull(cif), kpis(all, run -> true, data, window),
                daily(data.current, window), recent(data),
                failing(data.current, data.stepsByRun),
                screens(all, data), cifs(data), flaky(data));
    }

    @Transactional(readOnly = true)
    public Optional<ScreenDetail> screen(String app, String product, String flow, String period, String cif) {
        Optional<ScenarioStore.Menu> menu = store.menu(lower(app), product == null ? "" : product);
        if (menu.isEmpty()) {
            return Optional.empty();
        }
        Optional<ScenarioStore.Screen> screen = menu.get().screens().stream()
                .filter(candidate -> candidate.flow().equals(flow)).findFirst();
        if (screen.isEmpty()) {
            return Optional.empty();
        }
        Window window = Window.of(period);
        Data data = load(window, cif);
        List<Scenario> mine = data.scenarios.stream()
                .filter(s -> s.app.equals(lower(app)) && s.product.equals(product) && s.flow.equals(flow))
                .toList();
        Set<String> keys = new HashSet<>();
        mine.forEach(s -> keys.add(s.key));
        Predicate<Run> mineRun = run -> run.scenario != null ? keys.contains(run.scenario.key)
                : lower(run.summary.getApp()).equals(lower(app)) && flow.equals(run.summary.getFlow());
        List<Run> screenRuns = data.current.stream().filter(mineRun).toList();

        String catalogKey = lower(app) + "/" + flow;
        String catalog = catalogs.list().contains(catalogKey) ? "catalogs/" + catalogKey + ".yaml" : null;
        int ruleCount = products.load(lower(app), product).map(model -> model.rules().size()).orElse(0);

        Map<String, Run> last = lastFinished(data.current);
        Map<String, List<Run>> byScenario = byScenario(data.current);
        return Optional.of(new ScreenDetail(window.name, lower(app), product, menu.get().name(), flow,
                screen.get().name(), catalog, ruleCount, kpis(mine, mineRun, data, window),
                headings(lower(app), product, flow, mine, data),
                failing(screenRuns, data.stepsByRun),
                mine.stream().map(s -> row(s, last.get(s.key), byScenario.getOrDefault(s.key, List.of()))).toList()));
    }

    /** Every run of one saved scenario in the period, newest first. */
    @Transactional(readOnly = true)
    public Optional<History> history(String app, String product, String path, String period) {
        Window window = Window.of(period);
        Data data = load(window, null);
        Optional<Scenario> found = data.scenarios.stream()
                .filter(s -> s.app.equals(lower(app)) && s.product.equals(product) && s.path.equals(path))
                .findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Scenario s = found.get();
        List<HistoryRun> out = new ArrayList<>();
        for (Run run : data.current) {
            if (run.scenario != s) {
                continue;
            }
            RunSummary r = run.summary;
            Optional<TestRunStepEntity> failed = firstFailed(data.stepsByRun.get(r.getId()));
            out.add(new HistoryRun(r.getId().toString(), r.getSubmittedAt().toString(), r.getStatus(),
                    r.getCif(), r.getRunTrigger(), r.getBatchId() == null ? null : r.getBatchId().toString(),
                    r.getExecutorName(), run.durationMs, run.passed, run.total,
                    failed.map(TestRunStepEntity::getStepText).orElse(null),
                    failed.map(step -> firstLine(step.getErrorMessage())).orElse(null)));
        }
        return Optional.of(new History(s.app, s.product, s.path, s.title, s.screenName, s.heading,
                s.rules, out));
    }

    /**
     * Ties every run that has no scenario id yet to the one scenario its title
     * names TODAY, so renaming the scenario later does not cut it loose. Runs
     * whose title matches nothing or several stay unlinked. Idempotent: a
     * linked run is never touched again.
     *
     * @return how many runs were linked
     */
    @Transactional
    public int backfill() {
        Map<String, List<Scenario>> byKey = new HashMap<>();
        for (Scenario s : library()) {
            byKey.computeIfAbsent(key(s.app, s.flow, s.title), k -> new ArrayList<>()).add(s);
        }
        int linked = 0;
        for (RunSummary summary : runs.findUnlinkedSummaries()) {
            Scenario s = matchByName(summary, byKey);
            if (s != null) {
                linked += runs.linkScenario(summary.getId(), s.id);
            }
        }
        return linked;
    }

    /* ------------------------------ loading ----------------------------- */

    /** A dashboard period: this one, and the same length just before it. */
    record Window(String name, Instant now, Instant since, Instant previousSince) {

        /**
         * {@code all} is "tümü": every run ever recorded.
         *
         * <p>It exists because nothing here deletes a run — {@code TEST_RUN}
         * has no retention job — so a number that vanishes was only ever
         * excluded by the window, and without this option there was no way to
         * ask for the whole history. Ten years rather than {@code Instant.EPOCH}
         * so the "previous period" arithmetic below stays inside what
         * {@code Instant} can hold; its previous window is empty either way,
         * which is correct: there is nothing before everything.
         */
        static Window of(String period) {
            String key = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
            Duration length = switch (key) {
                case "24h" -> Duration.ofHours(24);
                case "30d" -> Duration.ofDays(30);
                case "all" -> Duration.ofDays(3650);
                default -> Duration.ofDays(7);
            };
            String name = switch (key) {
                case "24h", "30d", "all" -> key;
                default -> "7d";
            };
            Instant now = Instant.now();
            return new Window(name, now, now.minus(length), now.minus(length.multipliedBy(2)));
        }
    }

    /** A saved scenario as the dashboard needs it. */
    static final class Scenario {
        UUID id;
        /** The map key for this scenario: its id. A path is unique only within one product. */
        String key;
        List<String> rules;
        String app;
        String product;
        String productName;
        String flow;
        String screenName;
        String path;
        String title;
        String heading;
        boolean runnable;
        int ruleCount;
        boolean stale;
        List<String> risks;
    }

    /** A run, with its steps folded in and the scenario it matched, if any. */
    static final class Run {
        RunSummary summary;
        Scenario scenario;
        /** Tied to its scenario by title, not by a recorded id — counts as "≈". */
        boolean byName;
        int passed;
        int total;
        long durationMs;
    }

    private static final class Data {
        List<Scenario> scenarios;
        List<Run> current;
        List<Run> previous;
        Map<UUID, List<TestRunStepEntity>> stepsByRun;
    }

    /** @param cif only this CIF's runs; blank for all */
    private Data load(Window window, String cif) {
        Data data = new Data();
        data.scenarios = library();
        String onlyCif = blankToNull(cif);

        Map<String, List<Scenario>> byKey = new HashMap<>();
        Map<UUID, Scenario> byId = new HashMap<>();
        for (Scenario s : data.scenarios) {
            byKey.computeIfAbsent(key(s.app, s.flow, s.title), k -> new ArrayList<>()).add(s);
            byId.put(s.id, s);
        }

        data.stepsByRun = new HashMap<>();
        for (TestRunStepEntity step : steps.findForRunsSince(window.previousSince)) {
            data.stepsByRun.computeIfAbsent(step.getRunId(), id -> new ArrayList<>()).add(step);
        }
        data.stepsByRun.values().forEach(list -> list.sort(Comparator.comparingInt(TestRunStepEntity::getStepIndex)));

        data.current = new ArrayList<>();
        data.previous = new ArrayList<>();
        for (RunSummary summary : runs.findSummariesSince(window.previousSince)) {
            if (onlyCif != null && !onlyCif.equals(summary.getCif())) {
                continue;
            }
            Run run = new Run();
            run.summary = summary;
            if (summary.getScenarioId() != null) {
                // An id that names no scenario any more (deleted) matches nothing.
                run.scenario = byId.get(summary.getScenarioId());
            } else {
                run.scenario = matchByName(summary, byKey);
                run.byName = run.scenario != null;
            }
            for (TestRunStepEntity step : data.stepsByRun.getOrDefault(summary.getId(), List.of())) {
                run.total++;
                if ("PASSED".equals(step.getStatus())) {
                    run.passed++;
                }
                run.durationMs += step.getDurationMs() == null ? 0 : step.getDurationMs();
            }
            (summary.getSubmittedAt().isBefore(window.since) ? data.previous : data.current).add(run);
        }
        return data;
    }

    /** Every saved scenario of every product, with what the table rows already compute. */
    private List<Scenario> library() {
        List<Scenario> out = new ArrayList<>();
        for (ScenarioStore.Menu menu : store.menus()) {
            ProductModel model = products.load(menu.app(), menu.key()).orElse(null);
            Map<String, String> screenNames = new HashMap<>();
            menu.screens().forEach(screen -> screenNames.put(screen.flow(), screen.name()));
            Map<UUID, String> headingNames = new HashMap<>();
            products.headings(menu.app(), menu.key()).forEach(h -> headingNames.put(h.id(), h.name()));

            for (ProductService.Registered entry : products.registered(menu.app(), menu.key())) {
                ScenarioCard card = entry.card();
                Scenario s = new Scenario();
                s.id = entry.stored().id();
                s.key = s.id.toString();
                s.rules = card.ruleIds();
                s.app = menu.app();
                s.product = menu.key();
                s.productName = menu.name();
                s.flow = entry.stored().flow();
                s.screenName = screenNames.getOrDefault(s.flow, s.flow);
                s.path = entry.stored().path();
                s.title = card.title();
                s.heading = entry.stored().headingId() == null ? null : headingNames.get(entry.stored().headingId());
                s.runnable = card.stepCount() > 0 && card.problem() == null;
                s.ruleCount = card.ruleIds().size();
                s.stale = model != null && !products.changes(entry.stored(), model).isEmpty();
                s.risks = card.otherTags().stream()
                        .filter(tag -> tag.toLowerCase(Locale.ROOT).startsWith("risk:"))
                        .map(tag -> tag.substring("risk:".length()))
                        .toList();
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The one scenario a run belongs to, by title on its screen — or none when
     * the title matches nothing or several (two products, a duplicate title).
     */
    private static Scenario matchByName(RunSummary summary, Map<String, List<Scenario>> byKey) {
        List<Scenario> candidates = byKey.getOrDefault(
                key(summary.getApp(), summary.getFlow(), summary.getScenarioName()), List.of());
        if (summary.getProduct() != null && !summary.getProduct().isBlank()) {
            candidates = candidates.stream().filter(s -> s.product.equals(summary.getProduct())).toList();
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String key(String app, String flow, String title) {
        return lower(app) + "|" + (flow == null ? "" : flow) + "|"
                + (title == null ? "" : title.trim().toLowerCase(Locale.forLanguageTag("tr")));
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /* ---------------------------- computations ---------------------------- */

    private static boolean finished(Run run) {
        return "DONE".equals(run.summary.getStatus()) || "FAILED".equals(run.summary.getStatus());
    }

    /** Each scenario's LAST finished run among these runs (they arrive newest first). */
    private static Map<String, Run> lastFinished(List<Run> runs) {
        Map<String, Run> last = new HashMap<>();
        for (Run run : runs) {
            if (run.scenario != null && finished(run)) {
                last.putIfAbsent(run.scenario.key, run);
            }
        }
        return last;
    }

    /** Each scenario's runs, newest first. */
    private static Map<String, List<Run>> byScenario(List<Run> runs) {
        Map<String, List<Run>> out = new HashMap<>();
        for (Run run : runs) {
            if (run.scenario != null) {
                out.computeIfAbsent(run.scenario.key, k -> new ArrayList<>()).add(run);
            }
        }
        return out;
    }

    /**
     * @param inScope which runs belong to this view. The overview takes every
     *                run in the window; a screen takes its own, by the same
     *                rule {@link #screen} already uses (linked by scenario,
     *                or unlinked but on that app and flow). Passing the test
     *                rather than a ready list is what lets the PREVIOUS window
     *                be scoped identically, so the deltas compare like with
     *                like.
     */
    private Kpis kpis(List<Scenario> scope, Predicate<Run> inScope, Data data, Window window) {
        Map<String, Run> now = lastFinished(data.current);

        // Coverage, per scenario and by its LAST run: "how much of the library
        // has been touched at all". Deliberately not a run count -- it is the
        // one number that must NOT go up when the same scenario is run again.
        int runnable = 0;
        int ran = 0;
        boolean approximate = false;
        for (Scenario s : scope) {
            if (!s.runnable) {
                continue;
            }
            runnable++;
            Run last = now.get(s.key);
            if (last != null) {
                approximate |= last.byName;
                ran++;
            }
        }

        // Execution, per RUN. Runs the dashboard is still waiting on are left
        // out of passed/failed but counted in `runs`, so a scenario in flight
        // is visible without being called a result.
        int runs = 0;
        int runsPassed = 0;
        int runsFailed = 0;
        Set<String> failedSteps = new HashSet<>();
        long totalMs = 0;
        int timed = 0;
        for (Run run : data.current) {
            if (!inScope.test(run)) {
                continue;
            }
            runs++;
            if (!finished(run)) {
                continue;
            }
            if ("DONE".equals(run.summary.getStatus())) {
                runsPassed++;
            } else {
                runsFailed++;
                firstFailed(data.stepsByRun.get(run.summary.getId()))
                        .ifPresent(step -> failedSteps.add(step.getStepText()));
            }
            if (run.durationMs > 0) {
                totalMs += run.durationMs;
                timed++;
            }
        }

        int prevRuns = 0;
        int prevPassed = 0;
        int prevFailed = 0;
        for (Run run : data.previous) {
            if (!inScope.test(run)) {
                continue;
            }
            prevRuns++;
            if (!finished(run)) {
                continue;
            }
            if ("DONE".equals(run.summary.getStatus())) {
                prevPassed++;
            } else {
                prevFailed++;
            }
        }

        return new Kpis(scope.size(), runnable, scope.size() - runnable, ran, runnable - ran,
                runs, runsPassed, runsFailed, failedSteps.size(),
                timed == 0 ? null : totalMs / timed,
                new Previous(prevRuns, prevPassed, prevFailed), approximate);
    }

    private static List<Day> daily(List<Run> current, Window window) {
        LocalDate first = window.since.atZone(ZONE).toLocalDate();
        LocalDate last = window.now.atZone(ZONE).toLocalDate();
        Map<LocalDate, int[]> counts = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            counts.put(day, new int[5]);
        }
        for (Run run : current) {
            int[] row = counts.get(run.summary.getSubmittedAt().atZone(ZONE).toLocalDate());
            if (row == null) {
                continue;
            }
            switch (run.summary.getStatus() == null ? "" : run.summary.getStatus()) {
                case "DONE" -> row[0]++;
                case "FAILED" -> row[1]++;
                case "CANCELED" -> row[2]++;
                case "RUNNING" -> row[4]++;
                default -> row[3]++;
            }
        }
        List<Day> out = new ArrayList<>();
        counts.forEach((day, row) -> out.add(new Day(day.toString(), row[0], row[1], row[2], row[3], row[4])));
        return out;
    }

    private RecentRun recentRun(Run run) {
        RunSummary s = run.summary;
        String screen = run.scenario != null ? run.scenario.screenName : s.getFlow();
        return new RecentRun(s.getId().toString(), s.getScenarioName(),
                run.scenario == null ? null : run.scenario.path, screen, s.getFlow(), s.getStatus(),
                run.passed, run.total, run.durationMs, s.getSubmittedAt().toString(), s.getExecutorName(),
                s.getCif(), s.getRunTrigger());
    }

    private List<RecentRun> recent(Data data) {
        return data.current.stream().limit(RECENT_RUNS).map(this::recentRun).toList();
    }

    /**
     * Per CIF, over runs that recorded one (stage 2 onwards). The name is the
     * saved login's; a CIF no longer in users.yaml is shown bare.
     */
    private List<CifRow> cifs(Data data) {
        Map<String, String> names = new HashMap<>();
        for (var user : users.list()) {
            names.putIfAbsent(user.cif(), user.name());
        }
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> lastAt = new HashMap<>();
        for (Run run : data.current) {
            String cif = run.summary.getCif();
            if (cif == null) {
                continue;
            }
            int[] row = counts.computeIfAbsent(cif, k -> new int[3]);
            row[0]++;
            if ("DONE".equals(run.summary.getStatus())) {
                row[1]++;
            } else if ("FAILED".equals(run.summary.getStatus())) {
                row[2]++;
            }
            lastAt.putIfAbsent(cif, run.summary.getSubmittedAt().toString());
        }
        List<CifRow> out = new ArrayList<>();
        counts.forEach((cif, row) -> out.add(new CifRow(cif, names.get(cif), row[0], row[1], row[2],
                lastAt.get(cif))));
        out.sort((a, b) -> Integer.compare(b.runs(), a.runs()));
        return out;
    }

    /** Scenarios that both passed and failed in the period. */
    private static List<FlakyRow> flaky(Data data) {
        List<FlakyRow> out = new ArrayList<>();
        Map<String, List<Run>> grouped = byScenario(data.current);
        for (Scenario s : data.scenarios) {
            List<Run> mine = grouped.getOrDefault(s.key, List.of());
            int passed = (int) mine.stream().filter(r -> "DONE".equals(r.summary.getStatus())).count();
            int failed = (int) mine.stream().filter(r -> "FAILED".equals(r.summary.getStatus())).count();
            if (passed == 0 || failed == 0) {
                continue;
            }
            String lastFailedStep = mine.stream()
                    .filter(r -> "FAILED".equals(r.summary.getStatus()))
                    .findFirst()
                    .flatMap(r -> firstFailed(data.stepsByRun.get(r.summary.getId())))
                    .map(TestRunStepEntity::getStepText)
                    .orElse(null);
            out.add(new FlakyRow(s.app, s.product, s.path, s.title, s.screenName, passed, failed, lastFailedStep));
        }
        out.sort((a, b) -> Integer.compare(b.failed(), a.failed()));
        return out;
    }

    private static Optional<TestRunStepEntity> firstFailed(List<TestRunStepEntity> runSteps) {
        if (runSteps == null) {
            return Optional.empty();
        }
        return runSteps.stream().filter(step -> "FAILED".equals(step.getStatus())).findFirst();
    }

    private static boolean isLogin(TestRunStepEntity step) {
        String text = step.getStepText();
        return text != null && text.trim().toLowerCase(Locale.ROOT).startsWith("login");
    }

    /**
     * The steps runs failed on, counted in RUNS. The login is kept apart: its
     * text is redacted (every login reads the same) and a failed login says
     * something about the user or the session, not about the screen.
     */
    private static Failing failing(List<Run> scope, Map<UUID, List<TestRunStepEntity>> stepsByRun) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Set<String>> scenarios = new HashMap<>();
        Map<String, String> lastError = new HashMap<>();
        int loginFailures = 0;
        for (Run run : scope) {
            Optional<TestRunStepEntity> failed = firstFailed(stepsByRun.get(run.summary.getId()));
            if (failed.isEmpty()) {
                continue;
            }
            TestRunStepEntity step = failed.get();
            if (isLogin(step)) {
                loginFailures++;
                continue;
            }
            String text = step.getStepText() == null ? "" : step.getStepText().trim();
            counts.computeIfAbsent(text, k -> new int[1])[0]++;
            scenarios.computeIfAbsent(text, k -> new HashSet<>()).add(String.valueOf(run.summary.getScenarioName()));
            // Runs arrive newest first, so the first error seen is the latest.
            lastError.putIfAbsent(text, firstLine(step.getErrorMessage()));
        }
        List<FailingStep> top = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(TOP_STEPS)
                .map(e -> new FailingStep(e.getKey(), e.getValue()[0], scenarios.get(e.getKey()).size(),
                        lastError.get(e.getKey())))
                .toList();
        return new Failing(top, loginFailures);
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String line = text.strip().split("\\R", 2)[0];
        return line.length() > 220 ? line.substring(0, 220) + "…" : line;
    }

    private static List<ScreenBar> screens(List<Scenario> all, Data data) {
        Map<String, Run> last = lastFinished(data.current);
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Scenario> first = new LinkedHashMap<>();
        for (Scenario s : all) {
            String key = s.app + "|" + s.product + "|" + s.flow;
            first.putIfAbsent(key, s);
            int[] row = counts.computeIfAbsent(key, k -> new int[5]);
            row[0]++;
            if (!s.runnable) {
                row[4]++;
                continue;
            }
            Run run = last.get(s.key);
            if (run == null) {
                row[3]++;
            } else if ("DONE".equals(run.summary.getStatus())) {
                row[1]++;
            } else {
                row[2]++;
            }
        }
        List<ScreenBar> out = new ArrayList<>();
        counts.forEach((key, row) -> {
            Scenario s = first.get(key);
            out.add(new ScreenBar(s.app, s.product, s.productName, s.flow, s.screenName,
                    row[0], row[1], row[2], row[3], row[4]));
        });
        return out;
    }

    private List<HeadingBar> headings(String app, String product, String flow, List<Scenario> mine, Data data) {
        Map<String, Run> last = lastFinished(data.current);
        Map<String, int[]> counts = new LinkedHashMap<>();
        counts.put("Başlıksız", new int[6]);
        products.headings(app, product).stream()
                .filter(h -> flow.equals(h.flow()))
                .forEach(h -> counts.putIfAbsent(h.name(), new int[6]));
        for (Scenario s : mine) {
            int[] row = counts.computeIfAbsent(s.heading == null ? "Başlıksız" : s.heading, k -> new int[6]);
            row[0]++;
            if (!s.runnable) {
                row[2]++;
                continue;
            }
            row[1]++;
            Run run = last.get(s.key);
            if (run == null) {
                row[5]++;
            } else if ("DONE".equals(run.summary.getStatus())) {
                row[3]++;
            } else {
                row[4]++;
            }
        }
        List<HeadingBar> out = new ArrayList<>();
        counts.forEach((name, row) -> {
            if (row[0] > 0) {
                out.add(new HeadingBar(name, row[0], row[1], row[2], row[3], row[4], row[5]));
            }
        });
        return out;
    }

    /** @param runs this scenario's runs in the period, newest first */
    private static ScenarioRow row(Scenario s, Run last, List<Run> runs) {
        String status = !s.runnable ? "NOT_RUNNABLE" : last == null ? "NEVER_RUN" : last.summary.getStatus();
        List<String> last10 = new ArrayList<>();
        for (Run run : runs) {
            if (run.summary.getStatus() != null && !"RUNNING".equals(run.summary.getStatus())) {
                last10.add(0, run.summary.getStatus());
                if (last10.size() == 10) {
                    break;
                }
            }
        }
        return new ScenarioRow(s.path, s.title, s.heading, s.runnable, status,
                last == null ? null : last.summary.getSubmittedAt().toString(),
                last == null ? 0 : last.passed, last == null ? 0 : last.total,
                last == null ? null : last.durationMs, s.ruleCount, s.stale, s.risks,
                last == null ? null : last.summary.getCif(), last10);
    }
}
