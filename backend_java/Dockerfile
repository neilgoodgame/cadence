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
CMD ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
