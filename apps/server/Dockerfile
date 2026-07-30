# Build stage. Uses the maven image rather than the committed ./mvnw wrapper on
# purpose: the wrapper is a shell script, and a Windows checkout can carry CRLF
# line endings that a Linux shell refuses to run. `mvn` from the image has no
# such failure mode.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve from the pom alone, so this layer is cached until the pom
# actually changes — source edits do not re-download the world.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src/ src/
# Tests are skipped here: they need a live Supabase Postgres, which a build
# container has no business reaching. CI runs `mvnw verify` against a real one.
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root. Nothing here writes to disk; the filesystem can stay the image's.
RUN useradd --system --create-home --uid 10001 vaderai
USER vaderai

# *.jar rather than the exact name so a version bump in the pom does not break
# this line. -DskipTests leaves exactly one jar in target/.
COPY --from=build --chown=vaderai:vaderai /build/target/*.jar app.jar

# The default 25% heap cap is far too conservative for a container this size,
# and this service holds a buffer per live session.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENV SERVER_PORT=8787
EXPOSE 8787

# No HEALTHCHECK here on purpose. This image has no curl, and a check that only
# proves the JVM started would pass while the app is refusing connections. The
# real check lives in fly.toml and hits /actuator/health.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
