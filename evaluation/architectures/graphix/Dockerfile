# Graphix: AsterixDB 0.9.9-SNAPSHOT with the Graphix extension built in (pre-built package).
# STGraph uses the AsterixDB 0.9.9 release: Graphix needs patches that never reached a release, so its
# package is the closest available version.
ARG JAVA_VERSION=21
FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy

ARG GRAPHIX_VERSION=0.4.1

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl unzip && \
    rm -rf /var/lib/apt/lists/*

# The release zip also contains macOS metadata (__MACOSX), which is not extracted
RUN curl -fSL -o /tmp/graphix.zip \
        "https://github.com/graphix-asterixdb/package/releases/download/alpha-${GRAPHIX_VERSION}/graphix-${GRAPHIX_VERSION}-alpha.zip" && \
    unzip -q /tmp/graphix.zip "graphix-${GRAPHIX_VERSION}-alpha/*" -d /opt && \
    mv "/opt/graphix-${GRAPHIX_VERSION}-alpha" /opt/graphix && \
    rm /tmp/graphix.zip

WORKDIR /opt/graphix

CMD ["/bin/bash"]


