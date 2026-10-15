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

    private final ToolCallback click;
    private final ToolCallback selectOption;

    private DateTool(ToolCallback click, ToolCallback selectOption) {
        this.click = click;
        this.selectOption = selectOption;
    }

    /**
     * @return a tool bound to the browser, or null when the MCP server offers
     *         no click/select pair — in which case the agent simply does not
     *         get this tool, rather than getting one that fails on every call
     */
    static DateTool bind(ToolCallback[] callbacks) {
        ToolCallback click = find(callbacks, "browser_click");
        ToolCallback select = find(callbacks, "browser_select_option");
        return click == null || select == null ? null : new DateTool(click, select);
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

        try {
            step("open the field", click, "{\"target\":" + quote(target) + "}");
            // Year first, then month: each one redraws the day grid, so a day
            // chosen before them is thrown away. The month is sent as jQuery
            // UI's own 0-based value rather than its Turkish name — the name
            // depends on the page's locale, the number does not.
            step("choose the year", selectOption,
                    "{\"target\":\"" + YEAR + "\",\"values\":[" + quote(year) + "]}");
            step("choose the month", selectOption,
                    "{\"target\":\"" + MONTH + "\",\"values\":[" + quote(String.valueOf(month - 1)) + "]}");
            // text-is, not a substring: "2" must not match "12" or "20".
            step("click the day", click,
                    "{\"target\":" + quote(PICKER + " a:text-is(\"" + day + "\")") + "}");
        } catch (RuntimeException failed) {
            return "Could not set " + date + ": " + failed.getMessage()
                    + " The calendar may not have opened, or this box may not be a date field at all. "
                    + "Read the page and say what you see rather than trying again.";
        }

        return "Set to " + date + ". Clicking the day closes the calendar and writes the value, so "
                + "do not type into the box afterwards and do not press anything else in the calendar.";
    }

    /**
     * One MCP call, with its own log line.
     *
     * Logged here because BudgetedTool never sees these: they are made by this
     * class, not by the model, and a run whose date silently failed would
     * otherwise show a single set_date line and nothing about which of the
     * four steps went wrong.
     */
    private static void step(String what, ToolCallback tool, String arguments) {
        System.out.println("[uptest] set_date: " + what + " -> " + arguments);
        String result = tool.call(arguments);
        if (result != null && result.contains("### Error")) {
            throw new IllegalStateException(what + " failed: " + oneLine(result));
        }
    }

    private static String oneLine(String value) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
