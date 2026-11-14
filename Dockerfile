# Multi-stage build for the portfolio MCP server (Spring AI MCP, WebMVC/SSE transport).
# Exposes the BookMyShow + Uber APIs as MCP tools any model/host can call.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
# Point at the two backends for live tool calls (default to localhost; tools report
# "unavailable" gracefully if the backends are not running).
ENV BOOKMYSHOW_BASE_URL="http://book-my-show-backend:8080"
ENV UBER_BASE_URL="http://uber-api-gateway:8080"
EXPOSE 8093
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8093/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
