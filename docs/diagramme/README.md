# Diagramme – Tankerkönig-Polling-Modul

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

Öffnen: Datei in draw.io / der Desktop-App / der VS-Code-Erweiterung „Draw.io Integration" laden.
