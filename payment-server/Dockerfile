FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY main-backend/build.gradle ./main-backend/build.gradle
COPY payment-server/build.gradle ./payment-server/build.gradle
RUN chmod +x gradlew

COPY payment-server/src/main ./payment-server/src/main
RUN set -eux; \
    ./gradlew --no-daemon :payment-server:clean :payment-server:bootJar; \
    set -- payment-server/build/libs/*.jar; \
    [ "$#" -eq 1 ]; \
    cp "$1" app.jar

FROM eclipse-temurin:25-jre-noble AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin --no-create-home app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER app

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8090/actuator/health > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
