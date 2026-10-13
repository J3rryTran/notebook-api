FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace
COPY pom.xml pom.xml
COPY nb-common nb-common
COPY nb-notebook-service nb-notebook-service
RUN mvn clean package -pl nb-notebook-service -am -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN addgroup --system notebook && adduser --system --ingroup notebook notebook
USER notebook
COPY --from=builder /workspace/nb-notebook-service/target/nb-notebook-service-*.jar app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
