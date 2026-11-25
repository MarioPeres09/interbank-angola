FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /app
COPY ${SERVICE}/pom.xml ./
COPY ${SERVICE}/src ./src
RUN --mount=type=cache,id=interbank-maven,target=/root/.m2/repository,sharing=locked \
    mvn -B package && cp target/*.jar /app/service.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/service.jar ./service.jar
RUN install -d -o 10001 /app/data
USER 10001
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
