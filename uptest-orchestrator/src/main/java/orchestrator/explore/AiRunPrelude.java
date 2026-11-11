package orchestrator.explore;

import orchestrator.aitrace.AiTraces;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The login and the menu walk of an "AI ile çalıştır", done by code before the
 * agent is called at all.
 *
 * <p>The measured walk spent its first ~12 turns here — navigate, wait, read,
 * fill, click, wait, search, type the SMS code, click, wait — and every one of
 * those turns was paid for again on every later turn. None of it is a judgement:
 * the same fields, the same buttons, every run. The selectors are the
 * executor's, which has driven this login for months: NGC
 * {@code #userCode #userId #password #btnSubmit}, NGI {@code #cifNoLabel
 * #password}, the SMS step {@code #otpPassword} (tried, and skipped when it is
 * not there — exactly what NGC.login does). The menu is
 * {@code CommonActions.navigateTo}'s: the top item is an {@code i} with that
 * text, the child a visible {@code .main-nav} link, and the parent clicked again
 * when the child does not appear (three tries).
 *
 * <p>The MCP click and type tools take a CSS/Playwright selector as their
 * target — set_date has driven the calendar that way all along — so none of
 * this needs a snapshot reference.
 *
 * <p><b>Every failure falls back to today's behaviour.</b> A login that does not
 * go through hands the agent the full instruction with the login in it, as
 * before; a menu that does not open hands it the main menu to find. The prelude
 * can only remove turns, never make a run fail that would have passed.
 *
 * <p>A side effect worth having: when the login is done here, the password and
 * the SMS code never reach the model at all.
 */
final class AiRunPrelude {

    /** How far the code got before handing over. */
    enum Reached { NOTHING, LOGGED_IN, ON_SCREEN }

    /**
     * @param page the screen as it stands, for the agent's first turn; null
     *             when it could not be read
     */
    record Outcome(Reached reached, String page, String note) {
    }

    record Login(String app, String url, String cif, String userId, String password, String smsCode) {
    }

    static final String LOGIN_TOOL = "prelude_login";
    static final String MENU_TOOL = "prelude_menu";

    private static final int MENU_ATTEMPTS = 3;

    private final Map<String, ToolCallback> tools;
    private final ActionTape tape;
    private final Duration settle;

    /**
     * @param raw the MCP callbacks as they come, NOT the budgeted wrappers:
     *            these moves are not the model's and must not spend its budget
     */
    AiRunPrelude(ToolCallback[] raw, ActionTape tape, Duration settle) {
        this.tools = new LinkedHashMap<>();
        for (ToolCallback callback : raw) {
            tools.put(callback.getToolDefinition().name(), callback);
        }
        this.tape = tape;
        this.settle = settle;
    }

    Outcome run(Login login, String mainMenu, String screen) {
        long began = System.nanoTime();
        List<String> fields = fieldsOf(login);
        if (fields == null || !has("browser_navigate", "browser_type", "browser_click", "browser_snapshot")) {
            return new Outcome(Reached.NOTHING, null, "giriş koddan yapılamadı: uygulama ya da araç tanınmadı");
        }
        Map<String, Object> loginArgs = new LinkedHashMap<>();
        loginArgs.put("app", login.app());
        loginArgs.put("cif", login.cif());
        loginArgs.put("userId", login.userId());
        try {
            call("browser_navigate", "{\"url\":" + json(login.url()) + "}");
            for (int i = 0; i < fields.size(); i += 2) {
                type(fields.get(i), fields.get(i + 1));
            }
            call("browser_click", target("#btnSubmit"));
            if (present(login.smsCode()) && tryType("#otpPassword", login.smsCode())) {
                call("browser_click", target("#btnSubmit"));
            }
        } catch (RuntimeException failed) {
            record(LOGIN_TOOL, loginArgs, false, failed.getMessage(), began);
            return new Outcome(Reached.NOTHING, null, "giriş koddan yapılamadı: " + oneLine(failed.getMessage()));
        }
        record(LOGIN_TOOL, loginArgs, true, "Giriş yapıldı", began);

        if (!present(mainMenu) || !present(screen)) {
            return new Outcome(Reached.LOGGED_IN, page(), "menü bilinmiyor");
        }
        long menuBegan = System.nanoTime();
        Map<String, Object> menuArgs = new LinkedHashMap<>();
        menuArgs.put("mainMenu", mainMenu);
        menuArgs.put("screen", screen);
        String child = ".main-nav a:visible:text-is(" + json(screen) + ")";
        String parent = "i:text-is(" + json(mainMenu) + ")";
        String lastError = "";
        pause();
        for (int attempt = 1; attempt <= MENU_ATTEMPTS; attempt++) {
            try {
                call("browser_click", target(parent));
                call("browser_click", target(child));
                record(MENU_TOOL, menuArgs, true, mainMenu + " › " + screen + " açıldı", menuBegan);
                pause();
                return new Outcome(Reached.ON_SCREEN, page(), null);
            } catch (RuntimeException notOpen) {
                lastError = oneLine(notOpen.getMessage());
                System.out.println("[uptest] prelude: menu " + attempt + ". denemede acilmadi — " + lastError);
            }
        }
        record(MENU_TOOL, menuArgs, false, lastError, menuBegan);
        return new Outcome(Reached.LOGGED_IN, page(), "menü koddan açılamadı: " + lastError);
    }

    /**
     * What the agent is told, given how far the code got.
     *
     * Nothing done: the full composed instruction, login and all — today's
     * walk. Logged in: the scenario and where the main menu is. On the screen:
     * the scenario only. In both of the last two the page as it stands comes
     * along between CompactingChatModel's markers, so the first turn needs no
     * snapshot and every later turn stops paying for it.
     *
     * @param composed ExploreController.compose's text, used only when the login failed
     * @param pageCap  the same cap BudgetedTool puts on any tool result
     */
    static String instruction(Outcome outcome, String scenario, String composed,
                              String mainMenu, String screen, int pageCap) {
        if (outcome.reached() == Reached.NOTHING) {
            return composed;
        }
        StringBuilder out = new StringBuilder();
        if (outcome.reached() == Reached.ON_SCREEN) {
            out.append("Giriş yapıldı ve \"").append(mainMenu).append("\" menüsündeki \"")
                    .append(screen).append("\" ekranı açık. Menüye tekrar gitme.\n\n");
        } else {
            out.append("Giriş yapıldı.");
            if (present(mainMenu)) {
                out.append(" Ana menü: ").append(mainMenu.trim()).append('.');
            }
            out.append("\n\n");
        }
        out.append(scenario == null ? "" : scenario.trim());
        String page = outcome.page();
        if (page != null && !page.isBlank()) {
            String capped = page.length() > pageCap ? page.substring(0, pageCap) + "\n[... kesildi]" : page;
            out.append("\n\nŞu anki ekran:\n").append(CompactingChatModel.SCREEN_OPEN).append('\n')
                    .append(capped).append('\n').append(CompactingChatModel.SCREEN_CLOSE);
        }
        return out.toString();
    }

    /** Selector, value pairs for the login form, or null for an app this does not know. */
    static List<String> fieldsOf(Login login) {
        String app = login.app() == null ? "" : login.app().toLowerCase(Locale.ROOT);
        if (!present(login.url()) || !present(login.cif()) || !present(login.password())) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        switch (app) {
            case "ngc" -> {
                if (!present(login.userId())) {
                    return null;
                }
                fields.addAll(List.of("#userCode", login.cif().trim(), "#userId", login.userId().trim(),
                        "#password", login.password().trim()));
            }
            case "ngi" -> fields.addAll(List.of("#cifNoLabel", login.cif().trim(),
                    "#password", login.password().trim()));
            default -> {
                return null;
            }
        }
        return fields;
    }

    /** Real keystrokes, as NGC.login's pressSequentially: these boxes are masked. */
    private void type(String selector, String text) {
        call("browser_type", "{\"target\":" + json(selector) + ",\"text\":" + json(text) + ",\"slowly\":true}");
    }

    /** The SMS box appears only sometimes; its absence is not a failure. */
    private boolean tryType(String selector, String text) {
        try {
            type(selector, text);
            return true;
        } catch (RuntimeException absent) {
            return false;
        }
    }

    private String call(String tool, String args) {
        String result = tools.get(tool).call(args);
        if (result != null && result.contains("### Error")) {
            throw new IllegalStateException(tool + ": " + oneLine(result.substring(result.indexOf("### Error"))));
        }
        return result;
    }

    /** The page as it stands, recorded on the tape so the next click's reference resolves. */
    private String page() {
        long began = System.nanoTime();
        try {
            String page = tools.get("browser_snapshot").call("{}");
            tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, page,
                    Duration.ofNanos(System.nanoTime() - began).toMillis());
            return page;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private void pause() {
        try {
            Thread.sleep(Math.max(0, settle.toMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One entry on the tape and in the AI trace for the whole step. Its
     * arguments carry the cif and user id but never the password or the SMS
     * code, which the individual calls would have.
     */
    private void record(String tool, Map<String, Object> args, boolean ok, String result, long began) {
        long ms = Duration.ofNanos(System.nanoTime() - began).toMillis();
        StringBuilder json = new StringBuilder("{");
        args.forEach((key, value) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(json(key)).append(':').append(json(value == null ? "" : value.toString()));
        });
        json.append('}');
        ActionTape.Outcome outcome = ok ? ActionTape.Outcome.OK : ActionTape.Outcome.ERROR;
        tape.record(tool, json.toString(), outcome, result, ms);
        AiTraces.tool(tool, json.toString(), outcome.name(), result, result, ms);
        System.out.println("[uptest] prelude: " + tool + " " + (ok ? "tamam" : "olmadi") + " (" + ms + " ms)");
    }

    private boolean has(String... names) {
        for (String name : names) {
            if (!tools.containsKey(name)) {
                return false;
            }
        }
        return true;
    }

    private static String target(String selector) {
        return "{\"target\":" + json(selector) + "}";
    }

    static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : (value == null ? "" : value).toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String oneLine(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }
}
