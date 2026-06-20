FROM eclipse-temurin:24-jdk-noble AS build
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:24-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/keys /app/media
COPY --from=build /app/build/libs/*.jar app.jar
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["./entrypoint.sh"]
CMD ["java", "-jar", "app.jar"]
