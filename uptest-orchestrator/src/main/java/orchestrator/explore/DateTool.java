package orchestrator.explore;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Setting a date, done by us instead of by the model.
 *
 * Three prompt rules were written for this and all three were broken. Typed in
 * one go, a masked box paints its empty mask back and reports success. Typed
 * character by character into a box that is not empty, the digits interleave
 * with what was there and produce a date nobody asked for — "20/26/2509" in a
 * field that had already been set. Driven through the calendar, the agent
 * clicked a day without first moving the month, and reported it itself:
 * "clicking day 25 and pressing Tamam does not consistently update the field".
 *
 * None of that is a reasoning problem. It is a four-step sequence whose order
 * is load-bearing, and the fix is to stop asking. The agent says WHICH box and
 * WHAT date; the four calls are ours.
 *
 * The sequence is the one CommonActions.pickFromCalendar has been using
 * against this application since before the agent existed — year, then month,
 * then the day cell — because choosing a year or a month redraws the grid and
 * a day picked first is discarded.
 *
 * Addressing is by the widget's own ids, read off the page rather than
 * guessed: jQuery UI renders one #ui-datepicker-div at a time, with
 * #ui-datepicker-year and #ui-datepicker-month inside it. They are REAL select
 * elements, unlike this application's other dropdowns, so the ordinary
 * select-option tool drives them.
 */
public class DateTool {

    /** gg/aa/yyyy, the only shape these fields accept. */
    private static final Pattern DATE = Pattern.compile("^\\s*(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})\\s*$");

    private static final String PICKER = "#ui-datepicker-div";
    private static final String YEAR = "#ui-datepicker-year";
    private static final String MONTH = "#ui-datepicker-month";

    private static final String NEXT = PICKER + " .ui-datepicker-next";
    private static final String PREV = PICKER + " .ui-datepicker-prev";

    /** Further than this and the year/month lists are used rather than the arrows. */
    static final int MAX_ARROWS = 24;

    /**
     * The month the open calendar shows (jQuery UI's own drawYear/drawMonth,
     * else the lists' values) and whether its year/month lists can be used.
     */
    static final String SHOWN = """
            () => {
              const d = document.querySelector('#ui-datepicker-div');
              if (!d) return 'SHOWN none';
              const vis = e => !!e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length)
                && getComputedStyle(e).visibility !== 'hidden';
              const y = d.querySelector('select.ui-datepicker-year');
              const m = d.querySelector('select.ui-datepicker-month');
              let yy = null, mm = null;
              const i = window.jQuery && jQuery.datepicker && jQuery.datepicker._curInst;
              if (i && typeof i.drawYear === 'number') { yy = i.drawYear; mm = i.drawMonth; }
              else { if (y) yy = +y.value; if (m) mm = +m.value; }
              return 'SHOWN ' + yy + ' ' + mm + ' ' + (vis(y) ? 1 : 0) + ' ' + (vis(m) ? 1 : 0);
            }""";

    private static final Pattern SHOWN_LINE = Pattern.compile("SHOWN (\\d{4}) (\\d{1,2}) ([01]) ([01])");

    /** What the open calendar shows; month 0-based, as jQuery UI counts. */
    record Shown(int year, int month, boolean yearList, boolean monthList) {
    }

    private final ToolCallback click;
    private final ToolCallback selectOption;
    private final ToolCallback evaluate;

    private DateTool(ToolCallback click, ToolCallback selectOption, ToolCallback evaluate) {
        this.click = click;
        this.selectOption = selectOption;
        this.evaluate = evaluate;
    }

    /**
     * @return a tool bound to the browser, or null when the MCP server offers
     *         no click/select pair — in which case the agent simply does not
     *         get this tool, rather than getting one that fails on every call
     */
    static DateTool bind(ToolCallback[] callbacks) {
        ToolCallback click = find(callbacks, "browser_click");
        ToolCallback select = find(callbacks, "browser_select_option");
        ToolCallback evaluate = find(callbacks, "browser_evaluate");
        if (evaluate != null && (evaluate.getToolDefinition().inputSchema() == null
                || !evaluate.getToolDefinition().inputSchema().contains("\"function\""))) {
            evaluate = null;
        }
        return click == null || select == null ? null : new DateTool(click, select, evaluate);
    }

    private static ToolCallback find(ToolCallback[] callbacks, String name) {
        for (ToolCallback callback : callbacks) {
            if (name.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    @Tool(name = "set_date",
            description = "Set a date field. Give the field's reference or selector and the date as "
                    + "gg/aa/yyyy. This opens the field's calendar and picks year, month and day in the "
                    + "order the widget needs. Use this for EVERY date: do not type into a date box and "
                    + "do not drive the calendar yourself.")
    public String setDate(
            @ToolParam(description = "The date field itself: its reference from a snapshot, or a CSS "
                    + "selector for it") String target,
            @ToolParam(description = "The date to set, as gg/aa/yyyy — for example 25/09/2026")
            String date) {

        Matcher parts = DATE.matcher(date == null ? "" : date);
        if (!parts.matches()) {
            return "\"" + date + "\" is not a date. Give it as gg/aa/yyyy, for example 25/09/2026.";
        }
        String day = String.valueOf(Integer.parseInt(parts.group(1)));
        int month = Integer.parseInt(parts.group(2));
        String year = parts.group(3);

        if (month < 1 || month > 12) {
            return "There is no month " + month + ". Give the date as gg/aa/yyyy.";
        }

        String opened;
        try {
            opened = step("open the field", click, "{\"target\":" + quote(target) + "}");
            // Measured 13.11.2026, tape 4bc5c9f0, "Başlangıç Vadesi": the
            // calendar opened showing November 2026, but its year list is
            // hidden, and selecting in it waited out MCP's five seconds. So
            // the month on show is READ, and a near month is reached with the
            // arrows a person would press; the lists only for a far one.
            Shown shown = shown();
            int wanted = Integer.parseInt(year) * 12 + (month - 1);
            int away = shown == null ? Integer.MAX_VALUE : wanted - (shown.year() * 12 + shown.month());
            if (shown != null && Math.abs(away) <= MAX_ARROWS) {
                for (int i = 0; i < Math.abs(away); i++) {
                    step(away > 0 ? "next month" : "previous month", click,
                            "{\"target\":" + quote(away > 0 ? NEXT : PREV) + "}");
                }
            } else {
                // Year first, then month: each one redraws the day grid, so a day
                // chosen before them is thrown away. The month is sent as jQuery
                // UI's own 0-based value rather than its Turkish name — the name
                // depends on the page's locale, the number does not.
                step("choose the year", selectOption,
                        "{\"target\":\"" + YEAR + "\",\"values\":[" + quote(year) + "]}");
                step("choose the month", selectOption,
                        "{\"target\":\"" + MONTH + "\",\"values\":[" + quote(String.valueOf(month - 1)) + "]}");
            }
            // By ROLE, the link a person sees. Measured 13.11.2026 (ai-run
            // 4e5a83fa): "#ui-datepicker-div a:text-is("27")" matched TWO
            // links — a second copy with class "nonprintable" that is no link
            // to the accessibility tree — and Playwright refused it (strict
            // mode). Its own message named the one that is: getByRole('link',
            // { name: '27' }). The exact name ("s") keeps "2" off "12" and "20".
            step("click the day", click,
                    "{\"target\":" + quote(PICKER + " >> internal:role=link[name=\"" + day + "\"s]") + "}");
        } catch (RuntimeException failed) {
            return "Could not set " + date + ": " + failed.getMessage()
                    + " The calendar may not have opened, or this box may not be a date field at all. "
                    + "Read the page and say what you see rather than trying again.";
        }

        return "Set to " + date + ". Clicking the day closes the calendar and writes the value, so "
                + "do not type into the box afterwards and do not press anything else in the calendar."
                + boxLine(opened);
    }

    /**
     * The Playwright line MCP ran to open the box, carried in the result so the
     * tape keeps a target for the box that does not depend on one page load —
     * what "Hızlı çalıştır" sends again (ReplayCalls). Empty when there was none.
     */
    private static String boxLine(String clicked) {
        String line = ReplayCalls.codeLine(clicked);
        return line.isEmpty() ? "" : "\n### Ran Playwright code\n```js\n" + line + "\n```";
    }

    /** Whether a set_date result — as the tape holds it, JSON-quoted — says the date was set. */
    static boolean wasSet(String result) {
        String text = BudgetedTool.plain(result);
        return text != null && text.startsWith("Set to");
    }

    /** The month on show, or null when it cannot be read (no evaluate, no calendar, old jQuery UI). */
    private Shown shown() {
        if (evaluate == null) {
            return null;
        }
        try {
            // Jackson, not quote(): the script has line breaks, which quote() leaves raw.
            String answer = FocusedTyping.resultOf(evaluate.call("{\"function\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(SHOWN) + "}"));
            Matcher m = answer == null ? null : SHOWN_LINE.matcher(answer);
            if (m == null || !m.find()) {
                return null;
            }
            Shown shown = new Shown(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    "1".equals(m.group(3)), "1".equals(m.group(4)));
            System.out.println("[uptest] set_date: takvim " + (shown.month() + 1) + "/" + shown.year()
                    + " gösteriyor");
            return shown;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return null;
        }
    }

    /**
     * One MCP call, with its own log line.
     *
     * Logged here because BudgetedTool never sees these: they are made by this
     * class, not by the model, and a run whose date silently failed would
     * otherwise show a single set_date line and nothing about which of the
     * four steps went wrong.
     */
    private static String step(String what, ToolCallback tool, String arguments) {
        System.out.println("[uptest] set_date: " + what + " -> " + arguments);
        String result = tool.call(arguments);
        if (result != null && result.contains("### Error")) {
            throw new IllegalStateException(what + " failed: " + oneLine(result));
        }
        return result;
    }

    private static String oneLine(String value) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
