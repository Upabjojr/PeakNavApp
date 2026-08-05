#!/usr/bin/env bash
# Build every distributable JAR in the project:
#   core     -> core/build/libs/core-<version>.jar            (library)
#   desktop  -> desktop/build/libs/peaknav-<version>.jar      (cross-platform fat jar)
#   headless -> headless/build/libs/headless-<version>.jar    (library)
#            -> headless/build/libs/peaknav-headless-<version>.jar (runnable fat jar)
#
# Gradle must run on JDK 17 (see AGENTS.md); the system JDK may be newer.
set -euo pipefail
cd "$(dirname "$0")"

J=-Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64

./gradlew "$J" :core:jar :desktop:jar :headless:jar :headless:renderJar "$@"

echo
echo "Built JARs:"
ls -lh core/build/libs/*.jar desktop/build/libs/*.jar headless/build/libs/*.jar
