# Diagramme – TankerMax

## Polling-Modul

Die Datei [`tankermax-polling.drawio`](tankermax-polling.drawio) enthält drei Seiten
(in [draw.io](https://app.diagrams.net) als Reiter erreichbar):

1. **ER-Diagramm (3NF)** – das normalisierte Datenmodell mit allen Tabellen,
   Spalten, Primär-/Fremdschlüsseln und zusammengesetzten Eindeutigkeiten. Es
   entspricht den JPA-Entitäten im Paket `de.lembergmax.tankermax.polling.domain`,
   aus denen Hibernate (`ddl-auto=update`) das Schema erzeugt.
2. **Komponenten** – das Zusammenspiel von Scheduler, Diensten, API-Client,
   Ratenbegrenzer, Repositories und externen Systemen (Tankerkönig-API, MariaDB).
3. **Ablauf (Polling + Anreicherung)** – der Entscheidungsfluss eines Abfragezyklus
   inklusive der Vorrang-Logik der Detail-Anreicherung und der Stillstandserkennung.

## Web-Modul

Die Datei [`tankermax-web.drawio`](tankermax-web.drawio) zeigt das Web-Modul (Profil `web`):
Browser → statisches Dashboard und REST-API (`WebApiController`) → `StationQueryService` →
`JdbcTemplate` → MariaDB, dazu Sicherheits-Filter, Fehlerbehandlung und die Profil-Topologie
(`ingest` kopflos, `web` mit eingebettetem Web-Server). Die Web-Schicht liest die Daten nur;
befüllt wird die Datenbank vom `ingest`-Profil.

Öffnen: Datei in draw.io / der Desktop-App / der VS-Code-Erweiterung „Draw.io Integration" laden.
