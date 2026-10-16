FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/keys /app/media
COPY --from=build /app/build/libs/*.jar app.jar
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["./entrypoint.sh"]
# ExitOnOutOfMemoryError: an OOM otherwise leaves the JVM up but broken (it once killed
# Tomcat's acceptor thread, so staging stopped accepting connections for hours while systemd
# still saw the container running) - exiting lets the service's Restart=always recover it.
# MaxRAMPercentage: the JVM defaults to 25% of RAM, ~250MB on staging's 1GB t4g.micro.
# NativeMemoryTracking=summary: the 2026-10-16 OOM showed container RSS well past the heap
# cap (~796MB RSS vs. a ~480MB heap at the time), meaning most of the growth was off-heap
# (metaspace, thread stacks, code cache, GC structures) - this is what actually explains that
# gap. "summary" not "detail": low enough overhead to leave running permanently on a
# 600MB-capped container; "detail" costs meaningfully more for call-site-level granularity
# this app doesn't need day to day. Read it with `jcmd <pid> VM.native_memory summary` - this
# image is JRE-only (no jcmd), so run that via a one-off companion container instead:
# `docker run --rm --pid=container:cadence-backend eclipse-temurin:25-jdk-noble jcmd 1
# VM.native_memory summary` - rather than permanently shipping a full JDK in this image.
# UseG1GC: without this, JVM ergonomics silently pick Serial GC on a container this small
# (HotSpot's "server class machine" memory threshold is ~1.75GB, regardless of CPU count -
# confirmed via `jcmd <pid> VM.flags` showing -XX:+UseSerialGC on both the 600MB staging cap
# and a 1600MB local what-if). Serial grows the heap eagerly under any load burst and almost
# never shrinks it back; a repeatable local stress test (infra/scripts/stress-test-backend.sh)
# confirmed G1 alone doesn't fix this either - both collectors parked committed heap exactly
# flat for 5+ idle minutes after a burst, zero decommit. G1 is still the better choice (~2x
# the throughput of Serial on the same test, with no errors where Serial had some), but
# getting the actual decommit behavior needs G1PeriodicGCInterval below, forcing a concurrent
# idle-time collection that the JVM otherwise never schedules on its own.
# G1PeriodicGCInterval/G1PeriodicGCInvokesConcurrent: forces a GC after 60s of inactivity,
# concurrent (not stop-the-world) so it doesn't pause request handling - this is what actually
# releases committed-but-unused heap back toward the ceiling after a load burst passes,
# rather than leaving it parked until the next full/mixed collection happens to run.
CMD ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-XX:NativeMemoryTracking=summary", "-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=60000", "-XX:+G1PeriodicGCInvokesConcurrent", "-jar", "app.jar"]
