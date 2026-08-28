package com.ykb.uplift.uptest.playwright.common;

import java.nio.file.Path;

/**
 * Publishes the current run's working directory to the Cucumber glue.
 *
 * The hooks have no other way to learn it: {@code Main.run()} takes only a
 * String[] and a ClassLoader, so there is no context object to pass a run id
 * through. The server sets this immediately before invoking Cucumber and
 * clears it afterwards.
 *
 * Deliberately an InheritableThreadLocal rather than a system property:
 * Cucumber executes scenarios on the calling thread (we never pass
 * {@code --threads}), so the runner thread that sets this is the thread the
 * hooks run on, and two runs in one JVM cannot clobber each other's paths.
 *
 * Lives in the playwright package, not in server/, to keep the dependency
 * direction server -> playwright. The glue must not know about the web layer.
 */
public final class RunContext {

    private static final InheritableThreadLocal<Path> RUN_DIR = new InheritableThreadLocal<>();

    private RunContext() {
    }

    public static void set(Path runDir) {
        RUN_DIR.set(runDir);
    }

    public static void clear() {
        RUN_DIR.remove();
    }

    /**
     * The current run's directory, or {@code null} when Cucumber was started
     * outside the server (e.g. {@code mvn test} via RunCucumberTest). Callers
     * must supply their own fallback for that case.
     */
    public static Path get() {
        return RUN_DIR.get();
    }
}
