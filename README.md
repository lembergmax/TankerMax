# TankerMax

TankerMax sammelt die Spritpreise der [Tankerkönig-API](https://creativecommons.tankerkoenig.de)
und stellt sie in einem schlanken Dashboard mit Preisverlauf dar. Die Anwendung ist eine Spring-Boot-4-
Anwendung auf Java 21 und speichert Tankstellen, Marken, Kraftstoffarten und Preisbeobachtungen in
dritter Normalform in einer MariaDB.

## Zwei Profile

Die Anwendung läuft um eine gemeinsame Datenbank in zwei Betriebsarten, die über das aktive Spring-
Profil gewählt werden (ein Profil ist Pflicht – `ActiveProfileGuard` bricht den Start sonst ab):

- **`ingest`** – kopfloser Pollingdienst ohne Web-Server. Fragt die konfigurierten Orte nach einem
  festen Zeitplan ab, speichert Stationen/Marken/Preise und reichert die Tankstellen um Detaildaten
  (Öffnungszeiten, Bundesland) an.
- **`web`** – schreibgeschütztes Dashboard (statische Single-Page-App unter `/` plus REST-API unter
  `/api`). Visualisiert die gesammelten Daten; löst selbst keine API-Aufrufe aus.

Beide Profile lassen sich auch gemeinsam in einem Prozess betreiben (`web,ingest`).

## Voraussetzungen

- **Java 21**
- **MariaDB** (Standard: Datenbank/Benutzer/Passwort jeweils `tankermax`). Das Schema legt Hibernate
  beim Start selbst an (`spring.jpa.hibernate.ddl-auto=update`); es gibt keine separaten Migrationen.
- **Docker** (optional) – nur für die Testcontainers-Integrationstests.

## Einrichtung

1. Repository klonen.
2. Die Vorlage `.env.example` nach `.env` kopieren und die Werte eintragen:
   - `TANKERKOENIG_API_KEY` – persönlicher API-Schlüssel von Tankerkönig.
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` – Zugangsdaten der MariaDB. Das Standard-`DB_URL` enthält
     `createDatabaseIfNotExist=true`, sodass die Datenbank beim ersten Start angelegt wird.

   Die `.env`-Datei ist per `.gitignore` vom Versionskontrollsystem ausgeschlossen.
3. Die fachlichen Einstellungen (abzufragende Orte, Zeitpläne, Drosselung, Dashboard-Adresse) stehen
   im Abschnitt **„Benutzer-Einstellungen"** in
   [`application.properties`](src/main/resources/application.properties).

## Starten

Mit dem mitgelieferten Maven-Wrapper (Windows: `.\mvnw.cmd`, Unix: `./mvnw`). Ein Profil ist immer
erforderlich:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=ingest"      # nur Polling (kopflos)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=web"         # nur Dashboard (http://127.0.0.1:8080)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=web,ingest"  # beides in einem Prozess
```

Gepacktes Jar bauen und starten:

```powershell
.\mvnw.cmd clean package
java -jar target\TankerMax-0.0.1-SNAPSHOT.jar --spring.profiles.active=web,ingest
```

## Tests

```powershell
.\mvnw.cmd test     # schnelle Unit-Tests (Surefire)
.\mvnw.cmd verify   # zusätzlich die Docker-gestützten Integrationstests (Failsafe, *IT)
```

Die Integrationstests werden ohne laufenden Docker-Daemon automatisch übersprungen. Ein
Abdeckungsbericht (JaCoCo) entsteht unter `target/site/jacoco`.

## Betrieb & Beobachtbarkeit

Im Profil `web` stehen die Actuator-Endpunkte `/actuator/health` und `/actuator/metrics` bereit. Im
kombinierten Lauf `web,ingest` meldet die Gesundheit über den Ingest-Indikator zusätzlich, wenn seit
zu langer Zeit keine erfolgreiche Preisabfrage mehr erfolgte. Im reinen `ingest`-Lauf (ohne Web-Server)
erfolgt die Beobachtung über die Protokollausgaben.

## Konventionen

Deutsche Texte mit echten Umlauten; ausführliches deutsches Javadoc; keine erläuternden Inline-Kommentare;
`final` für alle Methoden- und Konstruktorparameter; Lombok für reine Datenträger- und DI-Klassen. Weitere
Hinweise stehen in [`CLAUDE.md`](CLAUDE.md).
