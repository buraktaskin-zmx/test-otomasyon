package orchestrator.aitrace;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one bean that switches the trace on.
 *
 * Spring AI's OpenAI chat model, its tool-calling manager and the ChatClient
 * builder all take an {@code ObservationRegistry} if one exists, and fall
 * back to {@code ObservationRegistry.NOOP} if not. Nothing in this project
 * defined one — Spring Boot's observation module is not on the classpath — so
 * every per-turn observation Spring AI already makes went nowhere.
 *
 * This registry carries ONLY our handler. Spring AI's own handlers that log
 * whole prompts to the console are not attached, and must not be: a prompt
 * here carries the test login.
 */
@Configuration
public class AiTraceConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObservationRegistry observationRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new AiTraceObservationHandler());
        return registry;
    }
}
