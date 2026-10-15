package com.ykb.uplift.uptest.playwright.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "bugün" or "+3 gün" into the date the screen wants, AT RUN TIME.
 *
 * The problem it exists for: a generated scenario freezes whatever date the
 * generator computed. VariationPlanner used LocalDate.now() while writing the
 * file, so a scenario drafted on 7 October carries "08/10/2026" forever — valid
 * that week, in the past a fortnight later, and refused by a form whose maturity
 * date may not be behind today. The scenario rots while nothing about it
 * changed.
 *
 * Moving "today" from generation time to run time fixes that for good: the text
 * says what it MEANS ("three days out") instead of what that meant on one
 * particular morning, and the browser works out the rest. A scenario can then be
 * stored, reviewed, and run months later.
 *
 * Absolute dates still pass through untouched. An analyst who needs a specific
 * day — a known holiday, a date a record already carries — must be able to write
 * it, and a resolver that refused one would push them back to a step that cannot
 * do relatives at all.
 *
 * Turkish on purpose. These expressions sit in scenarios that analysts read and
 * edit; the step PATTERN is English because the vocabulary is, but the value in
 * quotes is test data and has always been in whatever language the data is.
 */
public final class RelativeDate {

    /** gg/aa/yyyy — the only shape these fields accept. */
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Already a date: 7/10/2026, 07/10/2026, 07.10.2026, 07-10-2026. */
    private static final Pattern ABSOLUTE =
            Pattern.compile("^\\s*\\d{1,2}[./-]\\d{1,2}[./-]\\d{4}\\s*$");

    /**
     * An offset: a sign, a number, a unit. The sign is optional and means "+",
     * because "3 gün" reads as three days from now to everyone who writes it.
     */
    private static final Pattern OFFSET =
            Pattern.compile("^\\s*([+-]?)\\s*(\\d{1,4})\\s*(gün|gun|hafta|ay|yıl|yil)\\s*$");

    /** Turkish locale, so "GÜN" folds onto "gün" rather than onto nothing. */
    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    private RelativeDate() {
    }

    /**
     * @param expression "bugün", "yarın", "dün", "+3 gün", "-1 hafta", "2 ay",
     *                   or an absolute gg/aa/yyyy
     * @param today      the day to count from; a parameter rather than a call to
     *                   now() so this can be tested without waiting for midnight
     * @return the date as gg/aa/yyyy
     * @throws AssertionError when the expression means nothing — loudly, because
     *         a date silently defaulting to today is the kind of quiet wrong
     *         this project keeps paying for
     */
    public static String resolve(String expression, LocalDate today) {
        String text = expression == null ? "" : expression.trim();

        if (ABSOLUTE.matcher(text).matches()) {
            // Passed through as written: the field's own entry logic already
            // handles the separator, and re-formatting it here would be a second
            // place that decides what a date looks like.
            return text;
        }

        String folded = text.toLowerCase(TR);
        switch (folded) {
            case "bugün", "bugun" -> {
                return today.format(FORMAT);
            }
            case "yarın", "yarin" -> {
                return today.plusDays(1).format(FORMAT);
            }
            case "dün", "dun" -> {
                return today.minusDays(1).format(FORMAT);
            }
            default -> {
                // falls through to the offset form below
            }
        }

        Matcher offset = OFFSET.matcher(folded);
        if (!offset.matches()) {
            throw new AssertionError("\"" + expression + "\" bir tarih degil. Kullanilabilecekler: "
                    + "gg/aa/yyyy, \"bugun\", \"yarin\", \"dun\", ya da \"+3 gun\" / \"-1 hafta\" / "
                    + "\"2 ay\" / \"1 yil\" gibi bir kayma.");
        }

        int amount = Integer.parseInt(offset.group(2));
        if ("-".equals(offset.group(1))) {
            amount = -amount;
        }

        LocalDate shifted = switch (offset.group(3)) {
            case "gün", "gun" -> today.plusDays(amount);
            case "hafta" -> today.plusWeeks(amount);
            case "ay" -> today.plusMonths(amount);
            default -> today.plusYears(amount);
        };
        return shifted.format(FORMAT);
    }

    /** The same, counted from the machine's today. */
    public static String resolve(String expression) {
        return resolve(expression, LocalDate.now());
    }
}
