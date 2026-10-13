FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace
COPY pom.xml pom.xml
COPY nb-common nb-common
COPY nb-execution-service nb-execution-service
RUN mvn clean package -pl nb-execution-service -am -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN addgroup --system notebook && adduser --system --ingroup notebook notebook
USER notebook
COPY --from=builder /workspace/nb-execution-service/target/nb-execution-service-*.jar app.jar
EXPOSE 8082 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
