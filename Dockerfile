# TankerMax – Auslieferungs-Abbild für OpenShip.
#
# Reines Maven (SPA ist unter src/main/resources/static/ eingecheckt, kein Node-Schritt).
# Tests werden übersprungen: der Standard-Build (`mvnw verify`) fährt Testcontainers-
# Integrationstests, die einen Docker-Daemon brauchen.
#
# Start/Port/DB/Profil kommen als Umgebungsvariablen aus der Deploy-Oberfläche
# (Spring: echte Env-Vars schlagen die optionale .env-Datei).

# ── Stufe 1: JAR bauen ────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS jar
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline || true
COPY src/ src/
RUN mvn -B -ntp -DskipTests clean package \
    && cp target/TankerMax-*.jar /build/app.jar

# ── Stufe 2: Laufzeit ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10003 --create-home --home-dir /app tankermax \
    && mkdir -p /app/data && chown -R tankermax:tankermax /app
WORKDIR /app
COPY --from=jar --chown=tankermax:tankermax /build/app.jar app.jar
USER tankermax

EXPOSE 8080

# Prognose-/ML-App: mehr Heap als die kleinen Dienste, aber gedeckelt (cgroup-Limits
# auf dem Pi aus). Nativ lief sie mit -Xmx1g; 768m reicht laut Betrieb.
ENV JAVA_TOOL_OPTIONS="-Xmx768m -XX:+UseSerialGC"

# tankermax.forecast.model-store-path=data/forecast-models ist RELATIV -> CWD muss /app sein.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
