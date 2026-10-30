package orchestrator.explore;

import orchestrator.explore.PlaywrightLine.Act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static orchestrator.explore.ExpectedVerifies.appendVerifies;
import static orchestrator.explore.ExpectedVerifies.enterFor;
import static orchestrator.explore.PlaywrightLine.*;

/**
 * A recorded agent walk, written back out as steps an analyst can read.
 *
 * <p>The walk cost tokens and minutes once; this turns it into a {@code
 * .feature} that costs neither, because the step vocabulary runs without a
 * model. What makes that possible is not clever inference — it is that the
 * MCP server states, in every single result, exactly what it did:
 *
 * <pre>
 *   ### Ran Playwright code
 *   await page.getByRole('link', { name: 'Detay', exact: true }).click();
 * </pre>
 *
 * <p>So the role and the accessible name of every action are already resolved
 * when the tape is written. {@link AriaSnapshot} is still consulted, but for
 * the questions that line cannot answer: what the menu link is called without
 * its "YENİ" badge, which cell tells one grid row from another, and what a
 * dropdown's label is.
 *
 * <p><b>Two names for one element, and when to trust which.</b> The echoed
 * line carries {@code exact: true} only when the server needed it to
 * disambiguate; without it the name is a SUBSTRING it considered sufficient,
 * and it really is shortened — the option this walk chose comes back as
 * {@code 'APPLE A.Ş'} while the screen says {@code APPLE A.Ş.}. A step built
 * from the shortened form would then fail the catalog check against an option
 * the screen never offered. So: an exact name is taken as given, and anything
 * else is re-read from the snapshot.
 *
 * <p><b>What it refuses to do.</b> An action with no vocabulary step is not
 * guessed at — it is left out and named in {@link Rendered#notes}, the same
 * discipline {@code EntryRow} and {@code DateVerb} follow. And the assertion
 * is never invented: the agent does not assert, it reads a snapshot and says
 * so in prose, so the THEN comes from the analyst's own expectation. That is
 * the right way round — the oracle is what the business expects, not what the
 * application happened to show.
 */
public final class TapeRenderer {

    /** One recorded call, as {@link ActionTape} wrote it. */
    public record Call(int seq, String tool, String args, String outcome, String result) {
    }

    /**
     * @param steps Gherkin lines, in order, without the login
     * @param notes what could not be expressed, and why — never silent
     */
    public record Rendered(List<String> steps, List<String> notes) {
    }

    /** The kuruş half of an amount box; the lira half is the one before it. */
    private static final String DECIMAL_SUFFIX = "-decimal";

    /** Reads nothing and changes nothing; a scenario has no line for these. */
    private static final List<String> SILENT = List.of(
            "browser_snapshot", "browser_find", "browser_wait_for", "browser_navigate",
            "browser_console_messages", "browser_network_requests", "browser_take_screenshot");

    /** A failed look at the page is a retry, not a failed action. */
    private static final List<String> SILENT_FAILURES = List.of("browser_snapshot", "browser_find");


    private TapeRenderer() {
    }

    /**
     * @param calls   the tape's entries, in order
     * @param mainMenu the top-level menu from the tape header; the first click
     *                 on it is where the login ends and the scenario begins
     * @param expectations the scenario's own BEKLENEN lines, which is where
     *                     the THEN comes from — see {@link #appendVerifies}
     */
    public static Rendered render(List<Call> calls, String mainMenu, List<String> expectations) {
        List<String> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        TapeScope scope = new TapeScope();

        int entry = lastMenuEntry(calls, mainMenu);
        if (entry > 0 && countMenuEntries(calls, mainMenu) > 1) {
            notes.add("Ajan ana menüye " + countMenuEntries(calls, mainMenu)
                    + " kez girdi; yalnızca sonuncusundan itibaren render edildi."
                    + " Öncekiler başarısız denemeler sayıldı — bu yürüyüşte 404 alıp"
                    + " baştan başlamış olabilir.");
        }

        java.util.Set<Integer> premature = prematureClicks(calls);
        List<String> keys = new ArrayList<>();

        boolean started = false;
        for (int i = 0; i < calls.size(); i++) {
            Call call = calls.get(i);
            scope.remember(call);
            // Everything before the LAST entry into the main menu is read for
            // its snapshots but produces no steps: it is either the login or
            // an attempt that failed and was started over.
            if (i < entry) {
                continue;
            }

            if (SILENT.contains(call.tool())) {
                continue;
            }
            if (!"OK".equals(call.outcome()) && SILENT_FAILURES.contains(call.tool())) {
                continue;
            }
            if (!"OK".equals(call.outcome())) {
                notes.add("#" + call.seq() + " " + call.tool() + " " + call.outcome().toLowerCase(Locale.ROOT)
                        + " oldu, adıma çevrilmedi.");
                continue;
            }

            // set_date makes its browser calls itself, so its entry carries no
            // echoed Playwright line: the box is a snapshot reference and the
            // date is an argument, and that is everything `enter date` needs.
            if ("set_date".equals(call.tool())) {
                if (!started) {
                    continue;
                }
                String date = argOf(call, "date");
                String field = scope.labelOf(refOf(call), "");
                if (call.result() == null || !call.result().startsWith("Set to") || date.isBlank()) {
                    notes.add("#" + call.seq() + " set_date tarihi yazamadı; satır üretilmedi.");
                } else if (field.isBlank()) {
                    notes.add("#" + call.seq() + " \"" + date + "\" tarihi yazıldı ama hangi alana"
                            + " olduğu çözülemedi; satır yazılmadı.");
                } else {
                    steps.add("enter date \"" + field + "\" \"" + date + "\"");
                }
                continue;
            }

            Act act = firstAct(call).orElse(null);
            if (act == null) {
                if (!started) {
                    continue;
                }
                if ("browser_press_key".equals(call.tool())) {
                    keys.add("#" + call.seq());
                    continue;
                }
                notes.add("#" + call.seq() + " " + call.tool()
                        + " için vocabulary'de karşılık yok, satır üretilmedi.");
                continue;
            }

            String ref = refOf(call);
            String caption = scope.caption(act, ref);

            // Everything before the main menu is the login, which the run
            // grafts in from the test user rather than replaying: it carries
            // the password and the SMS code, and LoginStep already owns it.
            if (!started) {
                if (act.isClick() && fold(caption).equals(fold(mainMenu))) {
                    started = true;
                    int at = nextActing(calls, i, scope);
                    if (at < 0) {
                        notes.add("Ana menüye tıklandı ama alt menü seçilmemiş.");
                        continue;
                    }
                    Call next = calls.get(at);
                    steps.add("navigate to \"" + mainMenu + "\" \""
                            + scope.caption(firstAct(next).orElse(act), refOf(next)) + "\"");
                    i = at;
                }
                continue;
            }

            // The calendar the agent opens to correct a date. `enter date`
            // drives the widget itself, so the icon and the day cell are
            // implementation of a step that is already written.
            if (act.isClick() && isCalendarClick(act)) {
                // A day clicked in the calendar can be the ONLY place a date
                // was ever entered: one walk never typed "Başlangıç Vadesi"
                // and set it by opening the calendar and clicking 27, so
                // dropping the click dropped the field. The value is read off
                // the box the click filled.
                String[] set = dateSetBy(calls, i, act);
                if (set != null) {
                    steps.add("enter date \"" + set[0] + "\" \"" + set[1] + "\"");
                } else {
                    notes.add("#" + call.seq() + " takvimden " + act.name() + ". gün seçildi ama"
                            + " hangi alanı ve hangi ayı doldurduğu okunamadı; 'enter date'"
                            + " satırını elle ekleyin.");
                }
                continue;
            }

            // Pressed too early, and pressed again once the form was filled.
            // See prematureClicks.
            if (premature.contains(i)) {
                notes.add("#" + call.seq() + " \"" + caption + "\" form doldurulmadan basılmış ve"
                        + " doldurulduktan sonra aynı elemana yeniden basılmış; erken olan adım"
                        + " olarak yazılmadı.");
                continue;
            }

            // Error recovery, not a step. See DIALOG_DISMISS.
            if (act.isClick() && fold(caption).equals(fold(DIALOG_DISMISS))) {
                notes.add("#" + call.seq() + " \"" + DIALOG_DISMISS + "\" tıklaması bir hata"
                        + " diyaloğunu kapatmış; adım olarak yazılmadı. Akışın gerçek parçasıysa"
                        + " elle ekleyin.");
                continue;
            }

            // A dropdown is two clicks: open the control, then pick the option.
            if (act.isClick() && (isTrigger(act.role()) || isTrigger(scope.roleOf(ref))
                    || act.role().isEmpty())) {
                int at = optionAfter(calls, i, ref, scope);
                Act option = at < 0 ? null : firstAct(calls.get(at)).orElse(null);
                if (option != null && "option".equals(option.role())) {
                    String chosen = scope.caption(option, refOf(calls.get(at)));
                    // The field is named by READING THE EFFECT BACK, not by
                    // guessing from the control that was opened. Climbing from
                    // an unopened combobox walks into the neighbouring field's
                    // label — measured: a Döviz Cinsi dropdown came out as
                    // `select "TL" from "Orijinal Fatura Tutarı"`, which is
                    // plausible, passes a catalog check, and is wrong. After
                    // the choice the value sits INSIDE its own control, so the
                    // climb starts from the right place.
                    scope.peek(calls, at);
                    // The agent names the control it opens ("Firma Seçimi
                    // combobox"), and that name is trusted ONLY when the
                    // screen itself carries it as a label. Reading the effect
                    // alone failed when the choice changed the page: the first
                    // snapshot afterwards was the next screen, where the same
                    // text sits as a static "Kurum: Sanko DBS Ödemeleri", and
                    // the dropdown came out as `from "Kurum"` — a word that
                    // names no control at all.
                    String field = scope.groundedLabel(elementOf(call));
                    if (field.isBlank()) {
                        field = scope.labelOfChosen(chosen);
                    }
                    // Last resort, and exact rather than guessed: the box the
                    // agent OPENED sits inside the box that its label
                    // introduces. That is structure, not proximity — the
                    // "Döviz Cinsi → Orijinal Fatura Tutarı" mistake came
                    // from climbing by position, which this does not do.
                    if (field.isBlank()) {
                        field = scope.ownerLabel(ref);
                    }
                    if (field.isBlank()) {
                        notes.add("#" + call.seq() + " bir listeden \"" + chosen + "\" seçildi ama"
                                + " hangi alanın listesi olduğu kesinleşmedi; satır yazılmadı."
                                + " 'select ... from ...' satırını elle ekleyin.");
                    } else {
                        steps.add("select \"" + chosen + "\" from \"" + field + "\"");
                    }
                    i = at;
                    continue;
                }
                // A click INTO a text box is focus, not a step: `click` drives
                // buttons and links and would time out on a box. The agent
                // clicks a date box before typing into it, which rendered as
                // `click "Başlangıç Vadesi"` above its own `enter date`.
                if ("textbox".equals(act.role()) || "textbox".equals(scope.roleOf(ref))) {
                    continue;
                }
            }

            // An option picked from a list whose opening was not recorded as
            // a click of its own. Still a choice, never a `click`: the option
            // list is not a button or a link, so `click "TL"` could not run.
            if (act.isClick() && "option".equals(act.role())) {
                String chosen = caption;
                scope.peek(calls, i);
                String field = scope.labelOfChosen(chosen);
                if (field.isBlank()) {
                    notes.add("#" + call.seq() + " bir listeden \"" + chosen + "\" seçildi ama"
                            + " hangi alanın listesi olduğu kesinleşmedi; satır yazılmadı."
                            + " 'select ... from ...' satırını elle ekleyin.");
                } else {
                    steps.add("select \"" + chosen + "\" from \"" + field + "\"");
                }
                continue;
            }

            // A radio is two different steps depending on where it lives. In a
            // result grid it picks a ROW and is named by the row's own data;
            // in a plain form section it is a FORK between screens and is
            // named by the text beside it. Reading both as a row wrote
            // nothing at all for "Oluşturma Tipi: Bilgi Girişi" — the fork
            // that decides which form opens — and reported it as a grid row
            // with no identifying cell.
            if (act.isClick() && isRadio(act, ref, scope)) {
                if (!scope.insideRow(ref)) {
                    String option = caption.isBlank() ? scope.labelOf(ref, "") : caption;
                    if (option.isBlank()) {
                        notes.add("#" + call.seq() + " bir seçenek işaretlendi ama adı çözülemedi;"
                                + " 'choose' satırını elle yazın.");
                    } else {
                        steps.add("choose \"" + option + "\"");
                    }
                    continue;
                }
                String identifier = scope.rowIdentifier(ref);
                if (identifier.isBlank()) {
                    notes.add("#" + call.seq() + " bir ızgara satırı seçildi ama satırı tanıtan"
                            + " bir hücre bulunamadı; 'select row' satırını elle yazın.");
                } else {
                    steps.add("select row \"" + identifier + "\"");
                }
                continue;
            }

            if (act.isClick()) {
                if (caption.isBlank()) {
                    notes.add("#" + call.seq() + " bir tıklama kaydedildi ama neye tıklandığı"
                            + " çözülemedi; satır yazılmadı.");
                    continue;
                }
                steps.add("click \"" + caption + "\"");
                continue;
            }

            // A value needs a field to belong to. Writing one with the
            // control's own caption -- a placeholder like "Seçiniz" -- would
            // produce a step naming a label the screen does not have.
            String field = scope.labelOf(ref, act.name());
            if (field.isBlank()) {
                notes.add("#" + call.seq() + " \"" + act.value() + "\" yazıldı ama hangi alana"
                        + " olduğu çözülemedi; satır yazılmadı.");
                continue;
            }

            // An amount is two boxes, lira then kuruş, and `enter amount`
            // splits a "1000,50" back into them. The kuruş box is reached by
            // id (`#toplamTutar-decimal`), so it is recognised by that id
            // rather than by a label it does not have.
            int at = nextActing(calls, i, scope);
            Act next = at < 0 ? null : firstAct(calls.get(at)).orElse(null);
            if (next != null && !next.isClick() && next.locator().endsWith(DECIMAL_SUFFIX)) {
                steps.add("enter amount \"" + field + "\" \"" + act.value() + "," + next.value() + "\"");
                i = at;
                continue;
            }
            steps.add(enterFor(field, act.value()));
        }

        if (!keys.isEmpty()) {
            notes.add(String.join(", ", keys) + " klavye tuşu basışları için vocabulary'de karşılık"
                    + " yok, satır üretilmedi (ajanın alanı düzeltme çabası olabilir).");
        }
        if (steps.isEmpty()) {
            notes.add("Bu yürüyüşten hiçbir adım çıkarılamadı — ajan ana menüye hiç ulaşmamış olabilir.");
        }
        reorderForm(steps, scope, notes);
        appendVerifies(expectations, scope, steps, notes);
        return new Rendered(steps, notes);
    }


    /**
     * Where the scenario really starts: the LAST time the agent opened the
     * main menu.
     *
     * A walk is not always one attempt. This application's menu fails
     * intermittently — CommonActions.navigateTo retries it three times for
     * exactly that reason — and when it does, the agent dismisses the error
     * and starts over from the menu. Rendering from the FIRST entry writes
     * the failed attempt into the scenario: a click on a screen that never
     * opened, then a dialog dismissal, then the real walk. Measured on a
     * Fatura Oluştur recording, which produced "click Fatura Oluştur" before
     * the firm had even been chosen.
     *
     * The risk is a scenario that legitimately visits the main menu twice;
     * that one loses its first half. It is reported rather than hidden, and
     * it is the better trade: a missing half is visible, a failed attempt
     * rendered as steps is not.
     */
    private static int lastMenuEntry(List<Call> calls, String mainMenu) {
        int last = 0;
        for (int i = 0; i < calls.size(); i++) {
            if (isMenuClick(calls.get(i), mainMenu)) {
                last = i;
            }
        }
        return last;
    }

    private static int countMenuEntries(List<Call> calls, String mainMenu) {
        return (int) calls.stream().filter(call -> isMenuClick(call, mainMenu)).count();
    }

    private static boolean isMenuClick(Call call, String mainMenu) {
        if (!"browser_click".equals(call.tool()) || !"OK".equals(call.outcome())) {
            return false;
        }
        return firstAct(call)
                .map(act -> !act.name().isBlank() && fold(act.name()).equals(fold(mainMenu)))
                .orElse(false);
    }

    /**
     * The caption this application puts on the button that closes an error
     * dialog — the same one CommonActions.DIALOG_DISMISS drives.
     *
     * A click on it is error RECOVERY, not a step. Rendered into a scenario it
     * is actively harmful: on the replay there is no dialog, so the step waits
     * its whole timeout for a button that is not coming. Dropped and reported,
     * because an analyst writing a negative scenario really may want it and
     * should be told it was taken out.
     */
    private static final String DIALOG_DISMISS = "Tamam";

    /** A control that opens a list rather than doing something on its own. */
    private static boolean isTrigger(String role) {
        return "textbox".equals(role) || "combobox".equals(role);
    }

    /**
     * Clicks the agent made too early: the same element is clicked again
     * later, and something was filled or chosen in between.
     *
     * A snapshot reference only lives while its page does — a navigation
     * renumbers everything — so the same reference clicked twice proves the
     * first click did not leave the page. With values going in between, that
     * is a submit refused because the form was still empty, then retried.
     * Measured on DBS Fatura Oluştur: "Fatura Oluştur" pressed before a Kurum
     * was chosen (the bank answered with a dialog), and "Devam" pressed the
     * moment "Bilgi Girişi" revealed the form — which painted "giriniz" under
     * every required field. Both were rendered, so the replay would have
     * repeated the agent's mistakes.
     *
     * Without anything in between it is left alone: pressing a "+" twice is
     * an action, not a retry.
     */
    private static java.util.Set<Integer> prematureClicks(List<Call> calls) {
        java.util.Set<Integer> early = new java.util.HashSet<>();
        for (int i = 0; i < calls.size(); i++) {
            Act first = clickOf(calls.get(i));
            String ref = refOf(calls.get(i));
            if (first == null || ref.isEmpty() || "option".equals(first.role())
                    || isTrigger(first.role()) || first.role().isEmpty()) {
                continue;
            }
            boolean changed = false;
            boolean anyAct = false;
            boolean stayed = staysOnPage(calls, i, ref);
            for (int j = i + 1; j < calls.size(); j++) {
                Call later = calls.get(j);
                if (!"OK".equals(later.outcome())) {
                    continue;
                }
                Act act = firstAct(later).orElse(null);
                if (act == null) {
                    continue;
                }
                if (!act.isClick() || "option".equals(act.role())) {
                    changed = true;
                    anyAct = true;
                    continue;
                }
                if (ref.equals(refOf(later)) && fold(act.name()).equals(fold(first.name()))) {
                    if (changed || (stayed && anyAct)) {
                        early.add(i);
                    }
                    break;
                }
                anyAct = true;
            }
        }
        return early;
    }

    /**
     * Whether the page the click was meant to leave still has the element.
     *
     * A reference dies with its page, so the first snapshot taken after the
     * click either lacks it (the page moved on) or still carries it (the
     * application refused and stayed). Measured: "Devam" pressed with a
     * mangled date in the form — the screen painted "Geçerli bir tarih
     * giriniz." and kept the same link — then pressed again once the date
     * was corrected by clicking through the calendar. Nothing was typed
     * between the two presses, so "something was filled in between" could
     * not see it.
     */
    private static boolean staysOnPage(List<Call> calls, int from, String ref) {
        for (int j = from + 1; j < calls.size(); j++) {
            Call next = calls.get(j);
            if (!"browser_snapshot".equals(next.tool()) || !"OK".equals(next.outcome())
                    || next.args() == null || next.args().contains("target")) {
                continue;
            }
            AriaSnapshot parsed = AriaSnapshot.of(next.result());
            return !parsed.isEmpty() && parsed.node(ref).isPresent();
        }
        return false;
    }

    private static final Pattern DATE_TEXT = Pattern.compile("^(\\d{2})/(\\d{2})/(\\d{4})$");

    /**
     * The date box a clicked calendar day just filled, as {label, value}, or
     * null when no snapshot after the click shows one.
     *
     * The box is the one whose value is a date ENDING in the day that was
     * clicked, in the first snapshot taken afterwards. That is evidence
     * rather than a guess: the label comes from the box, the value from what
     * it now holds, and the month and year are whatever the calendar had
     * selected, which the box reports for itself.
     */
    private static String[] dateSetBy(List<Call> calls, int from, Act click) {
        if (!"link".equals(click.role())) {
            return null;
        }
        int day = Integer.parseInt(click.name());
        for (int j = from + 1; j < calls.size() && j <= from + 6; j++) {
            Call next = calls.get(j);
            if (!"browser_snapshot".equals(next.tool()) || !"OK".equals(next.outcome())) {
                continue;
            }
            AriaSnapshot parsed = AriaSnapshot.of(next.result());
            for (AriaSnapshot.Node node : parsed.nodes()) {
                Matcher date = DATE_TEXT.matcher(node.text() == null ? "" : node.text().trim());
                if ("textbox".equals(node.role()) && date.matches()
                        && Integer.parseInt(date.group(1)) == day) {
                    String label = node.name().isBlank() ? parsed.labelOf(node.ref()) : node.name();
                    if (!label.isBlank()) {
                        return new String[]{label, node.text().trim()};
                    }
                }
            }
            return null;
        }
        return null;
    }

    /** The calendar's own controls: its icon, and a bare day number. */
    private static boolean isCalendarClick(Act act) {
        if ("img".equals(act.role()) && "...".equals(act.name())) {
            return true;
        }
        return "link".equals(act.role()) && act.name().matches("\\d{1,2}");
    }

    /**
     * The option a dropdown was finally given, looking past what the agent
     * did on the way.
     *
     * Opening a Select2 then typing into its search box, then opening it
     * again, then clicking the option is one choice. Reading only the NEXT
     * call took the typing for the answer: the choice came out as
     * {@code click "Seçiniz"} and {@code enter "Seçiniz" "151732"}, the second
     * naming the widget's own search box as if it were a field. Gives up at
     * the first call that is neither the same trigger nor typing into a
     * Select2 — the agent moved on without choosing.
     */
    private static int optionAfter(List<Call> calls, int from, String ref, TapeScope scope) {
        for (int j = from + 1; j < calls.size() && j <= from + 60; j++) {
            Call call = calls.get(j);
            scope.remember(call);
            if (SILENT.contains(call.tool()) || !"OK".equals(call.outcome())) {
                continue;
            }
            Act act = firstAct(call).orElse(null);
            if (act == null) {
                continue;
            }
            if ("option".equals(act.role())) {
                return j;
            }
            // The same widget is reached through a different reference each
            // time it is re-opened (its container, its inner box, its
            // search box), so "the same trigger" cannot be judged by ref: a
            // click on anything that IS a dropdown trigger counts.
            boolean reopened = act.isClick() && (ref.equals(refOf(call)) || isTrigger(act.role())
                    || (act.role().isEmpty() && (act.locator().contains("select2")
                    || act.locator().contains("s2id"))));
            boolean typedIntoSearch = !act.isClick()
                    && (act.locator().contains("select2") || "searchbox".equals(act.role()));
            if (!reopened && !typedIntoSearch) {
                return -1;
            }
        }
        return -1;
    }

    private static Act clickOf(Call call) {
        if (!"browser_click".equals(call.tool()) || !"OK".equals(call.outcome())) {
            return null;
        }
        Act act = firstAct(call).orElse(null);
        return act != null && act.isClick() ? act : null;
    }

    /** The line chains down to a radio, or the reference resolves to one. */
    private static boolean isRadio(Act act, String ref, TapeScope scope) {
        if ("radio".equals(act.innerRole()) || "radio".equals(act.role())) {
            return true;
        }
        return scope.roleOf(ref).equals("radio");
    }

    /**
     * The index of the next call that actually did something, or -1.
     *
     * Reads every call it walks past into the scope, which is not an aside:
     * the reference the NEXT action names was chosen from a snapshot taken
     * BETWEEN the two, so looking ahead without reading along resolves it
     * against a stale tree. Measured — the first version paired the menu
     * clicks correctly and then wrote the submenu's name with its "YENİ"
     * badge still on, because the snapshot carrying the badge as a separate
     * node had not been read yet.
     */
    private static int nextActing(List<Call> calls, int from, TapeScope scope) {
        for (int i = from + 1; i < calls.size(); i++) {
            Call call = calls.get(i);
            scope.remember(call);
            if (SILENT.contains(call.tool()) || !"OK".equals(call.outcome())) {
                continue;
            }
            if (firstAct(call).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    /** The label a field-writing step names, or null for any other step. */
    private static final Pattern FIELD_STEP = Pattern.compile(
            "^(?:select \"(?:[^\"]*)\" from \"([^\"]*)\""
                    + "|enter(?: date| amount)? \"([^\"]*)\" \"(?:[^\"]*)\")$");

    private static String fieldOf(String step) {
        Matcher match = FIELD_STEP.matcher(step);
        if (!match.matches()) {
            return null;
        }
        return match.group(1) != null ? match.group(1) : match.group(2);
    }

    /**
     * A form filled top to bottom, however the agent actually went.
     *
     * The agent is told to fill in the order the screen prints and does not
     * always: one walk chose "Döviz Cinsi" last and, because a currency
     * change clears the amounts, typed both amounts a second time. Written
     * as it happened that is a scenario in which the dropdown sits below the
     * boxes it resets. Screen order is also the safe order — whatever a field
     * reloads is printed BELOW it — so each run of consecutive field steps
     * is sorted by where the screen prints the field, and a field written
     * twice keeps its LAST value.
     *
     * A run is left exactly as it was when any of its fields was never
     * printed on a snapshot the walk saw: ordering by a guess is the thing
     * this exists to stop.
     */
    private static void reorderForm(List<String> steps, TapeScope scope, List<String> notes) {
        List<String> out = new ArrayList<>();
        boolean moved = false;
        int i = 0;
        while (i < steps.size()) {
            if (fieldOf(steps.get(i)) == null) {
                out.add(steps.get(i++));
                continue;
            }
            int end = i;
            while (end < steps.size() && fieldOf(steps.get(end)) != null) {
                end++;
            }
            List<String> run = steps.subList(i, end);
            boolean known = run.stream().allMatch(step -> scope.rankOf(fieldOf(step)) >= 0);
            if (!known || run.size() < 2) {
                out.addAll(run);
            } else {
                Map<String, String> last = new LinkedHashMap<>();
                for (String step : run) {
                    String key = fold(fieldOf(step));
                    last.remove(key);
                    last.put(key, step);
                }
                List<String> sorted = new ArrayList<>(last.values());
                sorted.sort(java.util.Comparator.comparingInt(step -> scope.rankOf(fieldOf(step))));
                moved |= !sorted.equals(run);
                out.addAll(sorted);
            }
            i = end;
        }
        if (moved) {
            notes.add("Form alanları ekrandaki yukarıdan aşağıya sırayla yazıldı; ajan sırayı"
                    + " bozmuş ya da bir alanı iki kez doldurmuştu (son değer kaldı).");
        }
        steps.clear();
        steps.addAll(out);
    }


    /**
     * Turkish letters folded to ASCII before anything is compared.
     *
     * Analysts write expectations without them constantly — this walk's own
     * BEKLENEN line says "oldugunu dogrula" — and the screen says "Fatura
     * Tutarı". Without folding, the label is simply not found and a perfectly
     * good expectation is reported as unmatched. ScreenLabels does the same
     * thing on the executor side and for the same reason.
     *
     * Only the COMPARISON is folded. Whatever is written into a step keeps the
     * screen's own spelling, because that is what the step has to match.
     */
    private static final String TURKISH = "çğıöşüâîû";
    private static final String ASCII = "cgiosuaiu";

    static String fold(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.toLowerCase(Locale.forLanguageTag("tr-TR"));
        StringBuilder out = new StringBuilder(lowered.length());
        for (char letter : lowered.toCharArray()) {
            int at = TURKISH.indexOf(letter);
            out.append(at >= 0 ? ASCII.charAt(at) : letter);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

}
