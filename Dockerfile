# Build stage: compiles the jar. Kept separate from the runtime image so the final
# image doesn't carry the JDK, Maven cache, or source tree.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

# Dependencies first, so `docker build` can cache this layer across source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline

COPY src ./src
RUN ./mvnw -q -DskipTests package

# Runtime stage: JRE only, just the jar.
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=build /build/target/java-spring-auth-service-claude-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
