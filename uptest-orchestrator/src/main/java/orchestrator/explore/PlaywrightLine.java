package orchestrator.explore;

import orchestrator.explore.TapeRenderer.Call;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code await page…} line the MCP server echoes for every action, taken
 * apart: role, accessible name, exactness, the value typed and the locator
 * used — plus the arguments the agent passed with the call.
 *
 * Split out of {@link TapeRenderer} unchanged.
 */
final class PlaywrightLine {

    private PlaywrightLine() {
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


    /** {@code getByLabel('Fatura No')}, the other way the server names a field. */
    private static final Pattern BY_LABEL =
            Pattern.compile("getByLabel\\('((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern TARGET_REF = Pattern.compile("\"target\"\\s*:\\s*\"([A-Za-z0-9]+)\"");

    /** One {@code await page…} line, taken apart. */
    record Act(String role, String name, boolean exact, String innerRole, String value,
                       String locator) {

        boolean isClick() {
            return value == null;
        }
    }

    private static final Pattern ELEMENT = Pattern.compile("\"element\"\\s*:\\s*\"([^\"]*)\"");

    /** The words the agent appends to a label when it describes a control. */
    private static final Pattern ELEMENT_SUFFIX = Pattern.compile(
            "\\s+(combobox|textbox|alanı|alani|kutusu|listesi|dropdown|seçici)$", Pattern.CASE_INSENSITIVE);

    /** What the agent called the control it acted on, with its own suffix off. */
    static String elementOf(Call call) {
        Matcher match = ELEMENT.matcher(call.args() == null ? "" : call.args());
        if (!match.find()) {
            return "";
        }
        return ELEMENT_SUFFIX.matcher(match.group(1).trim()).replaceFirst("").trim();
    }

    /** One string argument of the recorded call, or blank. */
    static String argOf(Call call, String name) {
        Matcher match = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(call.args() == null ? "" : call.args());
        return match.find() ? match.group(1).trim() : "";
    }

    static String refOf(Call call) {
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
    static Optional<Act> firstAct(Call call) {
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
}
