# =============================================================================
# Orchestrator: UI, validation, dispatch. Never opens a browser, so it gets a
# plain JRE base — no Playwright, no browsers, no ~200 MB driver bundle.
# That separation is the whole point of the two-module split.
# =============================================================================
ARG BASE_IMAGE=eclipse-temurin:21-jre-noble
FROM ${BASE_IMAGE}

ARG HTTP_PROXY=""
ARG HTTPS_PROXY=""
ARG NO_PROXY=""
ENV HTTP_PROXY=${HTTP_PROXY} HTTPS_PROXY=${HTTPS_PROXY} NO_PROXY=${NO_PROXY} \
    http_proxy=${HTTP_PROXY} https_proxy=${HTTPS_PROXY} no_proxy=${NO_PROXY}

# Corporate root CA — JVM cacerts only here: this service makes HTTPS calls
# (executors, later the vLLM endpoint) but never runs a browser, so the OS
# trust store is not in the picture. See the executor Dockerfile for the
# two-store case.
COPY docker/certs/ /tmp/corporate-certs/
RUN set -eux; \
    if ls -A /tmp/corporate-certs/*.crt >/dev/null 2>&1; then \
        for cert in /tmp/corporate-certs/*.crt; do \
            keytool -importcert -noprompt -trustcacerts \
                -alias "corp-$(basename "$cert" .crt)" \
                -file "$cert" \
                -cacerts -storepass changeit; \
        done; \
        echo "Imported corporate CAs into JVM cacerts"; \
    else \
        echo "No corporate CA supplied; skipping"; \
    fi; \
    rm -rf /tmp/corporate-certs

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged: this is the only service published to analysts.
RUN useradd --system --create-home --uid 10001 orchestrator
COPY --chown=orchestrator:orchestrator uptest-orchestrator/target/uptest-orchestrator-*.jar /app/orchestrator.jar

USER orchestrator
WORKDIR /app

ENV EXECUTORS=executor-1:9035,executor-2:9035,executor-3:9035 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

EXPOSE 8035

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8035/api/v1/executors || exit 1

ENTRYPOINT ["java", "-jar", "/app/orchestrator.jar"]
