package orchestrator.db;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the database MCP toolbox, and nothing else.
 *
 * Only {@link DbToolbox} becomes a bean. Publishing the McpSyncClient inside
 * it would hand it to Spring AI's MCP autoconfiguration, which gathers every
 * such bean into the ONE ToolCallbackProvider the browser agent is given;
 * publishing a second provider would make {@code ExploreController}'s
 * injection ambiguous. Both are silent failures at startup, so the
 * encapsulation is the feature.
 */
@Configuration
@ConditionalOnProperty(name = "uptest.db.enabled", havingValue = "true")
@EnableConfigurationProperties(DbSettings.class)
public class DbConfig {

    @Bean(destroyMethod = "close")
    DbToolbox dbToolbox(DbSettings settings) {
        return new DbToolbox(settings);
    }
}
