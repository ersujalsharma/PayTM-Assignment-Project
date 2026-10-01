# ============================================================================
# Multi-stage build. Stage 1 builds the fat jar with a pinned JDK 21 and the
# project's Maven wrapper so a clean checkout produces an identical artifact.
# Stage 2 runs it on a slim JRE for a small image and fast cold start.
# ============================================================================

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Leverage layer caching: copy wrapper + pom first, warm the dependency cache,
# then copy sources. Dependency downloads only re-run when pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests clean package

# ---- run ----
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

# Run as a non-root user.
RUN useradd -r -u 1001 appuser
USER appuser

COPY --from=build /app/target/seat-reservation-*.jar app.jar

EXPOSE 8080

# Container-aware JVM tuning for a small (e.g. 512MB free-tier) instance.
# - MaxRAMPercentage 60: leave headroom for thread stacks + off-heap/metaspace so
#   we don't OOM-kill under a burst.
# - SerialGC: lowest CPU/memory overhead on a sub-1-vCPU box (G1's background
#   threads just add contention when you only have a fraction of a core).
# - ActiveProcessorCount hint keeps thread pools sane on fractional CPUs.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=1"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
