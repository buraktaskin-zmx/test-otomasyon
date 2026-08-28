package orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The orchestrator: serves the analyst UI, validates Gherkin, dispatches runs to
 * a free executor and serves results. It never runs a test itself — that is the
 * executor's only job, and the reason these are two deployables.
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
