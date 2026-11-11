FROM eclipse-temurin:25-jre-alpine

# Built jar is copied in rather than built here: `./gradlew bootJar` first.
# Requires the plain jar to be disabled in build.gradle.kts, or this glob matches two files.
ARG JAR_FILE=build/libs/*.jar

RUN addgroup -S fintrace && adduser -S -G fintrace -s /sbin/nologin fintrace
USER fintrace
WORKDIR /app

COPY --chown=fintrace:fintrace ${JAR_FILE} app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
