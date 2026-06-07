# Mitgelieferte Drittanbieter-Assets (`vendor/`)

Diese Dateien werden bewusst lokal vorgehalten (kein CDN), damit das Dashboard ohne externe
Netzwerkanfragen funktioniert (Offline-Betrieb, DSGVO, strenge Content-Security-Policy). Sie sind
als nachverfolgte Abhängigkeiten zu behandeln: bei Sicherheits-/Funktionsupdates ersetzen und die
unten stehende SHA-256-Prüfsumme aktualisieren.

| Datei | Quelle / Version | SHA-256 |
|---|---|---|
| `lightweight-charts.standalone.production.js` | TradingView Lightweight Charts™ v5.2.0 (npm `lightweight-charts@5.2.0`) | `c0992580867c4912cc9385b3c2728315bcc1a76c7f1087dca908430fccdf31d7` |
| `fonts/plus-jakarta-sans-latin.woff2` | Plus Jakarta Sans (variabel, Subset *latin*), Google Fonts v12 | `153fc85b70298beeb1d61a5f723331649e7f23bb77302a66e61cb3e2fbdb5e79` |
| `fonts/plus-jakarta-sans-latin-ext.woff2` | Plus Jakarta Sans (variabel, Subset *latin-ext*), Google Fonts v12 | `38e3b8fd8045048eb311d90170a4429ed2c8f405852dc3d91b5af8452758703f` |

## Prüfsummen verifizieren

```powershell
Get-FileHash -Algorithm SHA256 vendor\lightweight-charts.standalone.production.js, `
  vendor\fonts\plus-jakarta-sans-latin.woff2, vendor\fonts\plus-jakarta-sans-latin-ext.woff2
```

## Schriftarten aktualisieren

Die beiden `woff2`-Dateien stammen aus der Google-Fonts-CSS2-API
(`Plus+Jakarta+Sans:wght@400;500;600;700;800`). Da es sich um eine variable Schrift handelt,
deckt je Subset eine Datei den gesamten Strichstärkenbereich ab. Bei einem Update die aktuellen
`woff2`-URLs aus der CSS2-Antwort laden, die Dateien ersetzen und die Prüfsummen hier anpassen.
Die `@font-face`-Regeln stehen in `../neo.css`.
