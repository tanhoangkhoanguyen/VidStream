# Build the jar first (./gradlew bootJar): it is arch-independent and carries the git version.
FROM eclipse-temurin:25-jre-noble AS layers
WORKDIR /app
COPY build/libs/vidstream.jar .
RUN java -Djarmode=tools -jar vidstream.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --no-create-home --uid 10001 vidstream
WORKDIR /app
# Least-changing layers first.
COPY --from=layers /app/extracted/dependencies/ ./
COPY --from=layers /app/extracted/spring-boot-loader/ ./
COPY --from=layers /app/extracted/snapshot-dependencies/ ./
COPY --from=layers /app/extracted/application/ ./
USER vidstream
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "vidstream.jar"]
