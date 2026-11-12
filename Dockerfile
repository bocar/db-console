FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd --system --uid 10001 dbconsole \
    && mkdir -p /config /audit \
    && chown -R dbconsole:dbconsole /app /audit

COPY --from=build --chown=dbconsole:dbconsole /workspace/target/db-console.jar /app/db-console.jar

ENV SPRING_CONFIG_ADDITIONAL_LOCATION="file:/config/db-console.yml,file:/config/datasources.yml"

USER dbconsole
EXPOSE 8080
VOLUME ["/audit"]

ENTRYPOINT ["java", "-jar", "/app/db-console.jar"]
