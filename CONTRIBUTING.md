# Mitwirken an TankerMax

Danke für dein Interesse an TankerMax! Beiträge sind willkommen – von Fehlerberichten über
Verbesserungsvorschläge bis hin zu Pull Requests.

## Fehler melden

Bitte ein [Issue](https://github.com/lembergmax/TankerMax/issues) anlegen und dabei angeben:

- verwendetes Profil (`ingest`, `web` oder `web,ingest`),
- Java- und MariaDB-Version,
- die relevanten Protokollausgaben,
- was erwartet wurde und was statt dessen passiert ist.

**Niemals** den eigenen Tankerkönig-API-Schlüssel, Datenbank-Zugangsdaten oder Inhalte der
`.env`-Datei in ein Issue kopieren.

## Entwicklungsumgebung

Voraussetzungen und Einrichtung stehen in der [README](README.md). Kurzfassung:

```bash
cp .env.example .env      # Werte eintragen
./mvnw verify             # baut, testet und prüft
```

Die Docker-gestützten Integrationstests (`*IT`) werden ohne laufenden Docker-Daemon automatisch
übersprungen; `./mvnw test` läuft auch ohne Docker.

## Pull Requests

1. Vom Branch `developer` abzweigen (nicht von `main`).
2. Änderungen möglichst klein und thematisch geschlossen halten.
3. `./mvnw verify` muss lokal fehlerfrei durchlaufen; neue Logik braucht Tests.
4. Pull Request gegen `developer` öffnen und kurz beschreiben, **was** sich ändert und **warum**.

## Code-Konventionen

Diese Punkte werden im Review geprüft:

- **Sprache:** Deutsche Texte mit echten Umlauten (ä/ö/ü/ß), keine `ae`/`oe`/`ue`-Umschreibungen.
- **Javadoc:** Ausführliches deutsches Javadoc an jeder Klasse, Methode und jedem Feld.
- **Keine Inline-Kommentare:** Der Code soll sich selbst erklären; erläuternde Kommentare gehören
  ins Javadoc.
- **`final`:** Alle Methoden- und Konstruktorparameter sind `final`.
- **Lombok:** `@RequiredArgsConstructor` für reine DI-Beans, `@UtilityClass` für Hilfsklassen.
  Explizite Konstruktoren nur, wo bei der Konstruktion wirklich etwas passiert.
- **Datenmodell:** Neue Entitäten bleiben in dritter Normalform. Das Schema erzeugt Hibernate per
  `ddl-auto=update`; es gibt keine separaten Migrationsskripte.

## Sicherheitslücken

Sicherheitsrelevante Funde bitte **nicht** als öffentliches Issue melden, sondern dem Weg in
[SECURITY.md](SECURITY.md) folgen.
