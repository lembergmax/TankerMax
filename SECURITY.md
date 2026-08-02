# Sicherheitsrichtlinie

## Unterstützte Versionen

TankerMax ist ein privat gepflegtes Projekt. Sicherheitskorrekturen fließen ausschließlich in den
Branch `main` ein; ältere Stände werden nicht rückwirkend gepflegt.

## Sicherheitslücke melden

Bitte **kein öffentliches Issue** anlegen. Statt dessen über
[GitHub Security Advisories](https://github.com/lembergmax/TankerMax/security/advisories/new)
einen privaten Bericht einreichen.

Hilfreich sind: betroffene Version bzw. Commit, aktives Profil (`ingest` / `web`), eine
Beschreibung der Auswirkung und – falls vorhanden – Schritte zur Nachstellung.

Eine erste Rückmeldung erfolgt in der Regel innerhalb von 14 Tagen.

## Hinweise zum sicheren Betrieb

- **Zugangsdaten** stehen ausschließlich in der `.env`-Datei, die per `.gitignore` von der
  Versionskontrolle ausgeschlossen ist. Der Tankerkönig-API-Schlüssel und die
  Datenbank-Zugangsdaten gehören niemals in ein Commit, ein Issue oder einen Screenshot.
- **Netzbindung:** Das Dashboard bindet standardmäßig auf `127.0.0.1` (`server.address`) und ist
  damit nur lokal erreichbar. Wer es mit `0.0.0.0` im Netz öffnet, betreibt es ohne
  Authentifizierung – dann bitte einen vorgelagerten Reverse-Proxy mit TLS und Zugangsschutz
  verwenden.
- **Actuator:** Im Profil `web` sind `/actuator/health`, `/actuator/info` und `/actuator/metrics`
  freigegeben und `show-details=always` gesetzt. Diese Endpunkte sollten nicht ungeschützt aus dem
  Internet erreichbar sein.
- **Datenbank:** Der Dienst braucht Schreibrechte nur auf die eigene Datenbank; ein dedizierter
  Benutzer ohne weitergehende Rechte genügt.
- **Schreibende Endpunkte** gibt es nicht: Die Web-Schicht liest ausschließlich.
