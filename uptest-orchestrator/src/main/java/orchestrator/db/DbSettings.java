package orchestrator.db;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the database MCP server is, and how to start it.
 *
 * Deliberately NOT in {@code .mcp.json}. That file is read by
 * {@code spring.ai.mcp.client.stdio.servers-configuration}, which builds ONE
 * ToolCallbackProvider out of every server it finds — and
 * {@code ExploreController.toolsFor} hands the browser agent all of them when
 * no allow-list applies (Ekran Keşfi). A database server declared there would
 * therefore end up in the browser agent's toolbox, and a Python process that
 * fails to start would take the browser agent down with it.
 *
 * {@code env} is MERGED over the orchestrator's own environment rather than
 * replacing it, so a password exported in the shell that started the server
 * reaches the child without being written into a properties file. Write the
 * value here only for things that are not secret.
 *
 * @param enabled whether the panel and its endpoints exist at all
 * @param command the executable — on the Mac, database-mcp/run.sh
 * @param args    arguments for it; usually none, run.sh carries them
 * @param env     DB_HOST / DB_PORT / DB_SERVICE / DB_USER / DB_PASSWORD
 * @param timeout how long one tool call may take; a cold Oracle connection
 *                is slow, so this is well above Spring AI's own 20s default
 */
@ConfigurationProperties("uptest.db")
public record DbSettings(
        boolean enabled,
        String command,
        List<String> args,
        Map<String, String> env,
        Duration timeout) {

    public DbSettings {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        command = command == null ? "" : command.trim();
    }

    /** The child's environment: ours first, these on top. */
    public Map<String, String> environment() {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        env.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                merged.put(key, value);
            }
        });
        return merged;
    }
}
