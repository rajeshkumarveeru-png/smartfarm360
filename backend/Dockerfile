
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# Copy the complete repository, including shared/i18n
COPY . .

WORKDIR /workspace/backend

RUN chmod +x gradlew
RUN ./gradlew clean bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/backend/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]