FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY cotacao-frete-service/pom.xml cotacao-frete-service/pom.xml
COPY pedidos-service/pom.xml pedidos-service/pom.xml
COPY transportadoras-stub/pom.xml transportadoras-stub/pom.xml
RUN mvn -B -pl cotacao-frete-service -am dependency:go-offline
COPY . .
RUN mvn -B -pl cotacao-frete-service -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /workspace/cotacao-frete-service/target/cotacao-frete-service-1.0.0.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
