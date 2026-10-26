package orchestrator.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ------------------------------------------------------------------
    // Reading the echoed Playwright line
    // ------------------------------------------------------------------

    private static final String CODE_HEADING = "### Ran Playwright code";

    /** {@code getByRole('link', { name: 'Detay', exact: true })}, name and flag optional. */
    private static final Pattern BY_ROLE = Pattern.compile(
            "getByRole\\('([a-zA-Z]+)'(?:\\s*,\\s*\\{([^}]*)})?\\)");

    private static final Pattern NAME = Pattern.compile("name:\\s*'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern EXACT = Pattern.compile("exact:\\s*true");
    /**
     * A value going in. {@code pressSequentially} is the same act typed key by
     * key — the agent is TOLD to type amounts that way, because the masked
     * boxes ignore a single fill — and reading only {@code fill} turned the
     * lira half of "Toplam Tutar" into {@code click "Toplam Tutar"}.
     */
    private static final Pattern FILL =
            Pattern.compile("\\.(?:fill|pressSequentially)\\('((?:[^'\\\\]|\\\\.)*)'\\)");

    /** {@code page.locator('#toplamTutar-decimal')}: a control reached by id. */
    private static final Pattern LOCATOR = Pattern.compile("page\\.locator\\('([^']*)'\\)");

    /** The kuruş half of an amount box; the lira half is the one before it. */
    private static final String DECIMAL_SUFFIX = "-decimal";

    /** {@code getByLabel('Fatura No')}, the other way the server names a field. */
    private static final Pattern BY_LABEL =
            Pattern.compile("getByLabel\\('((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern TARGET_REF = Pattern.compile("\"target\"\\s*:\\s*\"([A-Za-z0-9]+)\"");

    /** Reads nothing and changes nothing; a scenario has no line for these. */
    private static final List<String> SILENT = List.of(
            "browser_snapshot", "browser_find", "browser_wait_for", "browser_navigate",
            "browser_console_messages", "browser_network_requests", "browser_take_screenshot");

    /** A failed look at the page is a retry, not a failed action. */
    private static final List<String> SILENT_FAILURES = List.of("browser_snapshot", "browser_find");

    /** One {@code await page…} line, taken apart. */
    private record Act(String role, String name, boolean exact, String innerRole, String value,
                       String locator) {

        boolean isClick() {
            return value == null;
        }
    }

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
        Scope scope = new Scope();

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
     * The THEN, built from what the ANALYST expects — never from what the
     * screen showed.
     *
     * The distinction is the whole of it. The agent does not assert: it reads
     * a snapshot and describes it, so a verify derived from the walk would say
     * the application is right because the application said so, and a defect
     * would be written into the suite as a passing test. The expectation is
     * the one input that can contradict the screen, and it is the analyst's.
     *
     * What the tape contributes is the other half: whether the label exists on
     * the screen the walk ENDED on. A verify naming a field that was never
     * shown spends its full timeout and blames the wrong thing, and that is
     * knowable here, before anyone runs it.
     *
     * The two are then compared. When they disagree the analyst's value is
     * used and the difference is reported — because a disagreement means
     * either the expectation is stale or the application is wrong, and those
     * need opposite fixes.
     */
    private static void appendVerifies(List<String> expectations, Scope scope,
                                       List<String> steps, List<String> notes) {
        if (expectations == null || expectations.isEmpty()) {
            notes.add("Senaryonun BEKLENEN alanı boş, doğrulama satırı üretilmedi —"
                    + " bir THEN'i olmayan senaryo test değildir.");
            return;
        }
        Map<String, String> onScreen = scope.labelledValues();
        for (String expectation : expectations) {
            if (expectation == null || expectation.isBlank()) {
                continue;
            }
            String label = longestLabelIn(expectation, onScreen.keySet());
            if (label == null) {
                notes.add("\"" + expectation.trim() + "\" bir doğrulama satırına çevrilemedi:"
                        + " ajanın son gördüğü ekranda bu adda bir alan yok. Satırı elle yazın.");
                continue;
            }
            String value = valueIn(expectation, label);
            if (value.isBlank()) {
                notes.add("\"" + expectation.trim() + "\" içinde '" + label
                        + "' alanı bulundu ama beklenen değer okunamadı. Satırı elle yazın.");
                continue;
            }
            steps.add(verifyFor(label, value));

            String shown = onScreen.getOrDefault(label, "");
            if (!shown.isBlank() && !fold(shown).equals(fold(value))) {
                notes.add("'" + label + "' için beklediğiniz değer \"" + value
                        + "\", ajanın gördüğü \"" + shown + "\". Sizinki yazıldı —"
                        + " ya beklenti bayat ya uygulama hatalı, ve ikisi zıt düzeltme ister.");
            }
        }
    }

    /** gg/aa/yyyy, which {@code enter date} and its verify take as meaning. */
    private static final Pattern DATE = Pattern.compile("^\\d{1,2}[./-]\\d{1,2}[./-]\\d{4}$");

    /** 1.925,96 with an optional currency, which the amount verify normalises. */
    private static final Pattern AMOUNT =
            Pattern.compile("^[0-9][0-9.]*,[0-9]{2}(\\s*[A-Z]{2,3})?$");

    /**
     * The write verb the value's shape asks for.
     *
     * A date written with plain {@code enter} is the mistake DateVerb exists
     * to rewrite at generation time: {@code field equals} compares exactly, so
     * a literal date passes into a field that resolves it and the assertion
     * hunts for text the screen never shows. Here the shape is known, so the
     * right verb is written in the first place.
     *
     * Amounts are deliberately NOT guessed: this application's amount field is
     * two inputs, lira and kuruş, so a single recorded fill is only half of
     * one and a step built from it would set the wrong number.
     */
    private static String enterFor(String label, String value) {
        if (DATE.matcher(value).matches()) {
            return "enter date \"" + label + "\" \"" + value + "\"";
        }
        return "enter \"" + label + "\" \"" + value + "\"";
    }

    /** The verb the value's own shape asks for, as the type table does. */
    private static String verifyFor(String label, String value) {
        if (DATE.matcher(value).matches()) {
            return "verify the \"" + label + "\" has date \"" + value + "\"";
        }
        if (AMOUNT.matcher(value).matches()) {
            return "verify the \"" + label + "\" has amount \"" + value + "\"";
        }
        return "verify the \"" + label + "\" field equals \"" + value + "\"";
    }

    /**
     * The longest on-screen label the expectation names.
     *
     * Longest, because the short one is inside the long one on these screens:
     * an expectation about "Orijinal Fatura Tutarı" also contains "Fatura
     * Tutarı", and the shorter match would verify a different row of the same
     * dialog.
     */
    private static String longestLabelIn(String expectation, java.util.Set<String> labels) {
        String folded = fold(expectation);
        String best = null;
        for (String label : labels) {
            if (folded.contains(fold(label)) && (best == null || label.length() > best.length())) {
                best = label;
            }
        }
        return best;
    }

    /**
     * What the analyst wrote after the label, with the sentence's tail off.
     *
     * "Bayi Kodu 151732 oldugunu doğrula" is a sentence, not a field and a
     * value, so the value is whatever sits between the label and the first
     * word that is plainly part of the asking. The list is short and only
     * grows when a real expectation is not read — a longer list would start
     * eating values.
     */
    private static final List<String> TAIL = List.of(
            "oldugunu", "olduğunu", "olmali", "olmalı", "dogrula", "doğrula",
            "dogrulanmali", "doğrulanmalı", "gorunmeli", "görünmeli", "gosterilmeli",
            "gösterilmeli", "yazmali", "yazmalı", "olarak", "seklinde", "şeklinde");

    private static String valueIn(String expectation, String label) {
        String folded = fold(expectation);
        int at = folded.indexOf(fold(label));
        String rest = expectation.substring(Math.min(expectation.length(), at + label.length())).trim();

        int cut = rest.length();
        for (String tail : TAIL) {
            Matcher word = Pattern.compile("(?i)(^|\\s)" + Pattern.quote(tail) + "(\\s|$|\\.)")
                    .matcher(fold(rest));
            if (word.find()) {
                cut = Math.min(cut, word.start(1) == word.start() ? word.start() : word.start() + 1);
            }
        }
        return rest.substring(0, Math.max(0, Math.min(cut, rest.length()))).trim()
                .replaceAll("^[:\\-–—\\s]+", "").replaceAll("[.,;\\s]+$", "").trim();
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
    private static int optionAfter(List<Call> calls, int from, String ref, Scope scope) {
        for (int j = from + 1; j < calls.size() && j <= from + 12; j++) {
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
            boolean reopened = act.isClick() && ref.equals(refOf(call));
            boolean typedIntoSearch = !act.isClick() && act.locator().contains("select2");
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
    private static boolean isRadio(Act act, String ref, Scope scope) {
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
    private static int nextActing(List<Call> calls, int from, Scope scope) {
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
    private static void reorderForm(List<String> steps, Scope scope, List<String> notes) {
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

    private static final Pattern ELEMENT = Pattern.compile("\"element\"\s*:\s*\"([^\"]*)\"");

    /** The words the agent appends to a label when it describes a control. */
    private static final Pattern ELEMENT_SUFFIX = Pattern.compile(
            "\s+(combobox|textbox|alanı|alani|kutusu|listesi|dropdown|seçici)$", Pattern.CASE_INSENSITIVE);

    /** What the agent called the control it acted on, with its own suffix off. */
    private static String elementOf(Call call) {
        Matcher match = ELEMENT.matcher(call.args() == null ? "" : call.args());
        if (!match.find()) {
            return "";
        }
        return ELEMENT_SUFFIX.matcher(match.group(1).trim()).replaceFirst("").trim();
    }

    private static String refOf(Call call) {
        Matcher match = TARGET_REF.matcher(call.args() == null ? "" : call.args());
        return match.find() ? match.group(1) : "";
    }

    /**
     * The first {@code await page…} line of a result, taken apart.
     *
     * Only the first: a fill_form writes three fields in one call, and all
     * three belong to the login this renderer does not replay. Should a
     * multi-field call ever appear after the login, the extra fields are
     * reported rather than dropped — see the note in {@link #render}.
     */
    private static Optional<Act> firstAct(Call call) {
        String code = codeBlock(call.result());
        if (code.isBlank()) {
            return Optional.empty();
        }
        for (String line : code.split("\\R")) {
            if (!line.contains("page.")) {
                continue;
            }
            List<String[]> roles = new ArrayList<>();
            Matcher byRole = BY_ROLE.matcher(line);
            while (byRole.find()) {
                roles.add(new String[]{byRole.group(1), byRole.group(2) == null ? "" : byRole.group(2)});
            }
            Matcher fill = FILL.matcher(line);
            String typed = fill.find() ? unescape(fill.group(1)) : null;

            if (roles.isEmpty()) {
                // No role, but something was typed: the server writes
                // `page.locator('#userCode').fill(…)` for a control it
                // reached by id, and `getByLabel(…)` for one it reached by
                // label. Dropping these lost every `enter` of a form-filling
                // walk and reported each as "no vocabulary equivalent" — the
                // step existed, the parser could not see the call. The field
                // is then named from the snapshot, through the ref.
                Matcher located = LOCATOR.matcher(line);
                String locator = located.find() ? located.group(1) : "";
                if (typed == null) {
                    // A click on a control reached by id:
                    // `page.locator('#select2-bayiKodu-container').click()` is
                    // how the server opens a Select2 it found no role for.
                    // Skipping it left the option click that follows with no
                    // trigger, and it came out as `click "bayisanko1"`.
                    if (!locator.isEmpty() && line.contains(".click(")) {
                        return Optional.of(new Act("", "", false, "", null, locator));
                    }
                    continue;
                }
                Matcher labelled = BY_LABEL.matcher(line);
                return Optional.of(new Act("textbox",
                        labelled.find() ? unescape(labelled.group(1)) : "", false, "", typed, locator));
            }

            String[] first = roles.get(0);
            Matcher name = NAME.matcher(first[1]);
            return Optional.of(new Act(
                    first[0],
                    name.find() ? unescape(name.group(1)) : "",
                    EXACT.matcher(first[1]).find(),
                    roles.size() > 1 ? roles.get(1)[0] : "",
                    typed, ""));
        }
        return Optional.empty();
    }

    private static String codeBlock(String result) {
        if (result == null) {
            return "";
        }
        int start = result.indexOf(CODE_HEADING);
        if (start < 0) {
            return "";
        }
        String rest = result.substring(start + CODE_HEADING.length());
        int next = rest.indexOf("###");
        return (next < 0 ? rest : rest.substring(0, next)).replace("\\n", "\n");
    }

    private static String unescape(String value) {
        return value.replace("\\'", "'").replace("\\\"", "\"").trim();
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

    private static String fold(String value) {
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

    /**
     * The snapshots a reference can still be looked up in.
     *
     * Newest first, and a handful rather than one: the agent takes a reference
     * from whichever reading came last, and that is sometimes a
     * {@code browser_find} result rather than a full snapshot — the "Detay"
     * link of this walk was found that way. Both are the same tree in the same
     * shape, so both are parsed.
     */
    private static final class Scope {

        private static final int REMEMBERED = 6;

        private final Deque<AriaSnapshot> recent = new ArrayDeque<>();

        void remember(Call call) {
            if (call.result() == null) {
                return;
            }
            AriaSnapshot parsed = AriaSnapshot.of(call.result());
            if (parsed.isEmpty()) {
                return;
            }
            recent.addFirst(parsed);
            while (recent.size() > REMEMBERED) {
                recent.removeLast();
            }
            // The fullest form seen: the screen the fields were filled on is
            // the one with the most labelled boxes, and it is long gone from
            // `recent` by the time the walk ends.
            List<String> labels = parsed.controlLabels();
            if (labels.size() > fieldOrder.size()) {
                fieldOrder = labels;
            }
        }

        private List<String> fieldOrder = List.of();

        /** Where the screen prints this field, or -1 when it never did. */
        int rankOf(String label) {
            for (int i = 0; i < fieldOrder.size(); i++) {
                if (fold(fieldOrder.get(i)).equals(fold(label))) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * What to call the element: the echoed name when it was EXACT, the
         * snapshot's own otherwise. See the class note on the shortened
         * "APPLE A.Ş".
         */
        String caption(Act act, String ref) {
            if (act.exact() && !act.name().isBlank()) {
                return act.name();
            }
            for (AriaSnapshot snapshot : recent) {
                if (snapshot.node(ref).isPresent()) {
                    String plain = snapshot.plainName(ref);
                    if (!plain.isBlank()) {
                        return plain;
                    }
                }
            }
            return act.name();
        }

        /**
         * Reads a little way past a call, so the EFFECT of it can be seen.
         *
         * Two is enough: a choice is followed by a settle and then a
         * snapshot. More would start pulling in the next screen.
         */
        void peek(List<Call> calls, int from) {
            int seen = 0;
            for (int i = from + 1; i < calls.size() && seen < 2; i++) {
                int before = recent.size();
                remember(calls.get(i));
                if (recent.size() != before) {
                    seen++;
                }
            }
        }

        /**
         * The label of the field now DISPLAYING this value.
         *
         * The reliable way to name a dropdown: after the choice, the value
         * sits inside its own control, so climbing to the label starts from
         * the right place. Climbing from the control BEFORE it was opened
         * walks into whatever label precedes it in the form, which on the
         * DBS form meant a currency landing under "Orijinal Fatura Tutarı".
         *
         * Options are skipped on the way: the list that was open still
         * carries the same text, and its label is the listbox's neighbour
         * rather than the field's.
         */
        String labelOfChosen(String chosen) {
            for (AriaSnapshot snapshot : recent) {
                for (AriaSnapshot.Node node : snapshot.nodes()) {
                    if ("option".equals(node.role()) || !fold(node.caption()).equals(fold(chosen))) {
                        continue;
                    }
                    String label = snapshot.labelOf(node.ref());
                    if (!label.isBlank() && !fold(label).equals(fold(chosen))) {
                        return label;
                    }
                }
            }
            return "";
        }

        /**
         * The candidate, spelled as the SCREEN spells it, or blank when no
         * snapshot carries it as a label of a control.
         *
         * A bare text node is not enough: "Kurum" is printed on the form as
         * a heading-like caption beside a value, and is not a field. Only a
         * label whose next sibling is a control counts.
         */
        String groundedLabel(String candidate) {
            if (candidate == null || candidate.isBlank()) {
                return "";
            }
            for (AriaSnapshot snapshot : recent) {
                for (AriaSnapshot.Node node : snapshot.nodes()) {
                    if (fold(node.caption()).equals(fold(candidate)) && snapshot.labelsAControl(node.ref())) {
                        return node.caption();
                    }
                }
            }
            return "";
        }

        /** The field's label, for the steps that take one. */
        String labelOf(String ref, String fallback) {
            for (AriaSnapshot snapshot : recent) {
                if (snapshot.node(ref).isPresent()) {
                    String label = snapshot.labelOf(ref);
                    if (!label.isBlank()) {
                        return label;
                    }
                }
            }
            return fallback;
        }

        String rowIdentifier(String ref) {
            for (AriaSnapshot snapshot : recent) {
                if (snapshot.node(ref).isPresent()) {
                    String identifier = snapshot.rowIdentifier(ref);
                    if (!identifier.isBlank()) {
                        return identifier;
                    }
                }
            }
            return "";
        }

        /**
         * The label/value pairs of the screen the walk ended on.
         *
         * Newest first and merged, because the dialog a scenario asserts on
         * is usually the last thing read while the grid behind it was read a
         * call or two earlier — and an expectation may name either.
         */
        Map<String, String> labelledValues() {
            Map<String, String> pairs = new LinkedHashMap<>();
            for (AriaSnapshot snapshot : recent) {
                snapshot.labelledValues().forEach(pairs::putIfAbsent);
            }
            return pairs;
        }

        /** Whether the element sits in a grid row — see AriaSnapshot#insideRow. */
        boolean insideRow(String ref) {
            for (AriaSnapshot snapshot : recent) {
                if (snapshot.node(ref).isPresent()) {
                    return snapshot.insideRow(ref);
                }
            }
            return false;
        }

        String roleOf(String ref) {
            for (AriaSnapshot snapshot : recent) {
                Optional<AriaSnapshot.Node> node = snapshot.node(ref);
                if (node.isPresent()) {
                    return node.get().role();
                }
            }
            return "";
        }
    }
}
