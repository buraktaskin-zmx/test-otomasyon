package orchestrator.db;

import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

/**
 * The database MCP server, held OUTSIDE Spring's bean graph on purpose.
 *
 * Neither the {@link McpSyncClient} nor its
 * {@link SyncMcpToolCallbackProvider} is published as a bean, and that is the
 * whole point of this class. Spring AI's MCP autoconfiguration collects every
 * {@code McpSyncClient} bean into one provider, and
 * {@code ExploreController} injects a single {@code ToolCallbackProvider}; a
 * second published provider would also make that injection ambiguous. So the
 * browser agent's toolbox stays exactly what it was, and these tools reach
 * only the code that asks this class for them.
 *
 * Connecting is LAZY. The orchestrator must start even when the Python
 * process is missing, the VPN is down or Oracle refuses — all three were met
 * during this setup — and the cost of being wrong is a dead application
 * rather than one panel reporting an error. {@link #failure()} is what that
 * panel shows.
 */
public class DbToolbox implements AutoCloseable {

    private final DbSettings settings;

    private McpSyncClient client;
    private SyncMcpToolCallbackProvider provider;
    /** The last connection attempt's error, or null when connected. */
    private String failure;
    private boolean tried;

    public DbToolbox(DbSettings settings) {
        this.settings = settings;
    }

    /**
     * The tools, connecting on first use.
     *
     * Returns an empty array rather than throwing when the server cannot be
     * reached: the caller reports {@link #failure()} to the analyst, which
     * says WHY, where an exception stack in a 500 says only that something
     * broke.
     */
    public synchronized ToolCallback[] callbacks() {
        connect();
        return provider == null ? new ToolCallback[0] : provider.getToolCallbacks();
    }

    public synchronized List<String> names() {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : callbacks()) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }

    /** Null when the server answered; otherwise why it did not. */
    public synchronized String failure() {
        connect();
        return failure;
    }

    /** Drops the connection so the next call starts the process again. */
    public synchronized void reset() {
        closeQuietly();
        client = null;
        provider = null;
        failure = null;
        tried = false;
    }

    private void connect() {
        if (tried) {
            return;
        }
        tried = true;
        if (settings.command().isBlank()) {
            failure = "uptest.db.command tanimli degil";
            return;
        }
        try {
            ServerParameters parameters = ServerParameters.builder(settings.command())
                    .args(settings.args())
                    .env(settings.environment())
                    .build();
            StdioClientTransport transport =
                    new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
            McpSyncClient started = McpClient.sync(transport)
                    .requestTimeout(settings.timeout())
                    .build();
            started.initialize();
            client = started;
            provider = SyncMcpToolCallbackProvider.builder().mcpClients(started).build();
            failure = null;
        } catch (RuntimeException cause) {
            // The message is what the panel prints, so it carries the cause's
            // own text: "No such file or directory" and "ORA-01017" need
            // different fixes and a generic sentence names neither.
            failure = describe(cause);
            closeQuietly();
            client = null;
            provider = null;
        }
    }

    private static String describe(Throwable cause) {
        StringBuilder text = new StringBuilder();
        for (Throwable walk = cause; walk != null && text.length() < 400; walk = walk.getCause()) {
            String message = walk.getMessage();
            if (message != null && !message.isBlank() && text.indexOf(message) < 0) {
                if (text.length() > 0) {
                    text.append(" <- ");
                }
                text.append(message.trim());
            }
            if (walk.getCause() == walk) {
                break;
            }
        }
        return text.length() == 0 ? cause.getClass().getSimpleName() : text.toString();
    }

    private void closeQuietly() {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (RuntimeException ignored) {
            // Closing a client that never connected is not worth a stack trace.
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
        client = null;
        provider = null;
    }
}
