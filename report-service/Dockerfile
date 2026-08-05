# Build de contexto: raiz do repositório (projeto multi-módulo Maven).
# docker build -f report-service/Dockerfile .

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Camada de dependências: copia só os POMs primeiro para cache do Maven
COPY pom.xml .
COPY common-events/pom.xml common-events/pom.xml
COPY ingestion-service/pom.xml ingestion-service/pom.xml
COPY reconciliation-service/pom.xml reconciliation-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY report-service/pom.xml report-service/pom.xml
RUN mvn -B -pl report-service -am dependency:go-offline

# Código-fonte deste serviço e do módulo do qual ele depende
COPY common-events/src common-events/src
COPY report-service/src report-service/src
RUN mvn -B -pl report-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/report-service/target/report-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
