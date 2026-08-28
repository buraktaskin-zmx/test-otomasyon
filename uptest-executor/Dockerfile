# =============================================================================
# Executor: Cucumber + Playwright. Runs one scenario at a time.
#
# Base image must provide BOTH a JRE (17+) and the Playwright browsers with
# their system libraries. Microsoft's Playwright Java image does; the tag must
# match the Playwright version pinned in uptest-executor/pom.xml, because a
# driver newer than its browsers fails at launch.
#
# BASE_IMAGE is an ARG because mcr.microsoft.com is commonly blocked on
# corporate networks. Pull it once from outside and push to the internal
# registry, then build with:
#   --build-arg BASE_IMAGE=registry.yapikredi.local/playwright/java:v1.58.0-noble
#
# The image is built FROM A PREBUILT JAR rather than compiling inside Docker:
# the Maven build is already verified on the developer machine, and a
# multi-stage build would re-download every dependency through the corporate
# proxy on each rebuild. Run `mvn -DskipTests package` first — or use
# docker/build.ps1, which does both so the jar can never be stale.
# =============================================================================
ARG BASE_IMAGE=mcr.microsoft.com/playwright/java:v1.58.0-noble
FROM ${BASE_IMAGE}

# Proxy needs to exist at build time too (apt, keytool fetching nothing, etc.)
ARG HTTP_PROXY=""
ARG HTTPS_PROXY=""
ARG NO_PROXY=""
ENV HTTP_PROXY=${HTTP_PROXY} HTTPS_PROXY=${HTTPS_PROXY} NO_PROXY=${NO_PROXY} \
    http_proxy=${HTTP_PROXY} https_proxy=${HTTPS_PROXY} no_proxy=${NO_PROXY}

# The Playwright base does not ship curl, and without it the HEALTHCHECK below
# fails every time — the container would be marked unhealthy while serving
# perfectly well.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# Corporate root CA.
#
# Needed in TWO places: the OS trust store (Playwright/Chromium reads that) and
# the JVM cacerts (the executor's own HTTPS calls read that). Getting only one
# means TLS works in the browser but not in Java, or the reverse.
#
# certs/ may be empty — the build then does nothing, so this Dockerfile works
# unchanged outside the corporate network.
# ---------------------------------------------------------------------------
COPY docker/certs/ /tmp/corporate-certs/
RUN set -eux; \
    if ls -A /tmp/corporate-certs/*.crt >/dev/null 2>&1; then \
        cp /tmp/corporate-certs/*.crt /usr/local/share/ca-certificates/; \
        update-ca-certificates; \
        for cert in /tmp/corporate-certs/*.crt; do \
            keytool -importcert -noprompt -trustcacerts \
                -alias "corp-$(basename "$cert" .crt)" \
                -file "$cert" \
                -cacerts -storepass changeit; \
        done; \
        echo "Imported corporate CAs into OS trust store and JVM cacerts"; \
    else \
        echo "No corporate CA supplied; skipping"; \
    fi; \
    rm -rf /tmp/corporate-certs

# Per-run working directories. Also a volume mount point the orchestrator reads.
RUN mkdir -p /var/qa/runs && chown -R pwuser:pwuser /var/qa/runs

COPY --chown=pwuser:pwuser uptest-executor/target/uptest-executor-*.jar /app/executor.jar

# The Playwright image ships this unprivileged user with a writable home, which
# Chromium needs for its profile directories.
USER pwuser
WORKDIR /app

# ---------------------------------------------------------------------------
# Container-specific browser settings. The defaults in Hooks reproduce the VDI
# (headed system Edge) so IDE runs keep working; a container has no display and
# no Edge, so all three are overridden here.
# ---------------------------------------------------------------------------
ENV UPTEST_HEADLESS=true \
    UPTEST_BROWSER_CHANNEL="" \
    UPTEST_SLOWMO=0 \
    FEATURESFOLDER=/var/qa/runs \
    SIMULTANEOUSLYRUN=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

EXPOSE 9035

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:9035/cucumber/test || exit 1

ENTRYPOINT ["java", "-jar", "/app/executor.jar"]
