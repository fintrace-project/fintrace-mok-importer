# Pinned by digest so a base update is a commit, and an unchanged release still rebuilds for it (§7.8);
# Dependabot moves the digest
FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918

# Built jar is copied in rather than built here: `./gradlew bootJar` first.
# Requires the plain jar to be disabled in build.gradle.kts, or this glob matches two files.
ARG JAR_FILE=build/libs/*.jar

RUN addgroup -S fintrace && adduser -S -G fintrace -s /sbin/nologin fintrace
USER fintrace
WORKDIR /app

COPY --chown=fintrace:fintrace ${JAR_FILE} app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
