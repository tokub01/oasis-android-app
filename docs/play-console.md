# Oasis in der Google Play Console

Vorlage für den internen Test – das Gegenstück zu TestFlight. Die App für Play ist die Build-Variante `play`:
Paketname `de.velwebsolutions.oasis`, spricht mit `staging.get-oasis.com` (mit Staging-Gate) wie der TestFlight-Build,
mit R8 verkleinert und mit dem Upload-Schlüssel signiert. Google signiert sie für die Geräte neu (Play App Signing).

## Was du selbst tun musst (Schritt für Schritt)

1. **Entwicklerkonto** unter <https://play.google.com/console> anlegen (einmalig 25 US-$; Kontotyp „Organisation“
   oder „Privatperson“, Identitätsprüfung durch Google).
2. **App anlegen:** „App erstellen“ → Name „Oasis“, Standardsprache Deutsch (Deutschland), App (nicht Spiel),
   kostenlos (die Mitgliedschaft ist ein In-App-Abo). Die Erklärungen zu Richtlinien und US-Exportgesetzen bestätigen.
3. **Erste Version von Hand hochladen** (Google verlangt das, bevor die API Uploads annimmt):
   `./gradlew :app:bundlePlay` (lokal, mit `secrets.properties` → `OASIS_GATE_KEY` und dem Upload-Schlüssel unter
   `~/.android-keys/`) erzeugt `app/build/outputs/bundle/play/app-play.aab`. In der Console: Testen → Interner Test →
   Neuer Release → AAB hochladen. Dabei Play App Signing aktivieren (Standard: Google verwaltet den App-Signaturschlüssel,
   unser Schlüssel ist nur der Upload-Schlüssel).
4. **Tester:** Interner Test → Tester → E-Mail-Liste anlegen (bis 100 Google-Konten) → Link „Einladung annehmen“ an die
   Tester schicken.
5. **App-Inhalte** ausfüllen (Abschnitt unten): Datenschutzerklärung, App-Zugriff, Anzeigen, Einstufung,
   Zielgruppe, Datensicherheit, Behörden-Apps, Finanz- und Gesundheitsfunktionen.
6. **Store-Eintrag:** Texte, Icon, Feature-Grafik und Screenshots liegen in `app/src/main/play/listings/de-DE/`.
   Hochladen von Hand oder mit `./gradlew :app:publishPlayListing` (nach Schritt 7).
7. **Service-Konto für die automatischen Uploads:**
   - In der Google Cloud Console (Projekt `oasis-86acd` oder ein eigenes) die „Google Play Android Developer API“
     aktivieren, ein Service-Konto anlegen und einen JSON-Schlüssel erzeugen.
   - In der Play Console → Nutzer und Berechtigungen → Nutzer einladen: die E-Mail des Service-Kontos, für die App
     Oasis mit „Releases für Testtracks verwalten“ (und „Store-Eintrag verwalten“, wenn die Texte mit hochgehen sollen).
8. **GitHub-Secrets** im Repo `tokub01/oasis-android-app` (Settings → Secrets and variables → Actions):

   | Secret | Inhalt |
   |---|---|
   | `ANDROID_UPLOAD_KEYSTORE_B64` | `base64 -i ~/.android-keys/oasis-upload.jks` |
   | `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | Wert `keystore_password` aus `~/.android-keys/oasis-upload.properties` |
   | `ANDROID_UPLOAD_KEY_ALIAS` | `oasis-upload` |
   | `ANDROID_UPLOAD_KEY_PASSWORD` | Wert `key_password` aus derselben Datei |
   | `PLAY_SERVICE_ACCOUNT_JSON` | Inhalt der JSON-Datei des Service-Kontos |
   | `STAGING_GATE_KEY` | das Staging-Gate-Passwort (wie bei TestFlight) |
   | `GOOGLE_SERVICES_JSON_B64` | optional, für Push: `base64 -i app/google-services.json` (Firebase-Projekt `oasis-86acd`) |

   Zum Kopieren ohne Anzeigen: z. B. `base64 -i ~/.android-keys/oasis-upload.jks | pbcopy`, dann im Browser einfügen.
   Danach lädt jeder Push auf `develop` den Build in den internen Test (`.github/workflows/play-internal.yml`).
   Fehlen Secrets, überspringt der Workflow mit einem Hinweis.

Der Upload-Schlüssel und sein Passwort liegen nur auf diesem Mac unter `~/.android-keys/` (Rechte 600). **Sichere beide
zusätzlich in deinem Passwortmanager** – geht der Upload-Schlüssel verloren, kann man ihn bei Google zurücksetzen lassen,
das dauert aber einige Tage.

## App-Inhalte (Vorlagen)

### Datenschutzerklärung
`https://get-oasis.com/datenschutz`

### App-Zugriff (für die Prüfung durch Google)
„Alle oder einige Funktionen sind eingeschränkt“ → Anleitung:

> Oasis ist ein soziales Netzwerk; alle Funktionen setzen ein Konto voraus. Bitte mit dem Demokonto anmelden:
> Benutzername `mira`, Passwort siehe Feld „Passwort“. Danach sind Quelle, Entdecken, Oasen, Profil und Einstellungen
> sichtbar. Der Chat ist Ende-zu-Ende-verschlüsselt; zum Ansehen in „Chats“ die Chat-Passphrase eingeben.

Das Passwort des Demokontos und die Chat-Passphrase gehören nur in die Play Console, nicht in dieses Repository. Sie
stehen in den Testdaten des Laravel-Projekts (`database/seeders/DemoContentSeeder.php`) bzw. im iOS-UI-Test
(`UITests/ChatUITests.swift`). Voraussetzung: Das Demokonto `mira` existiert auf `staging.get-oasis.com`.

### Anzeigen
„Nein, meine App enthält keine Werbung.“

### Einstufung des Inhalts (IARC-Fragebogen)
- Kategorie: **Soziale Netzwerke, Foren, nutzergenerierte Inhalte und Kommunikation**.
- Gewalt, Sexualität, Sprache, kontrollierte Substanzen, Glücksspiel: **Nein** (Oasis selbst zeigt solche Inhalte nicht;
  Nutzerinhalte werden moderiert).
- Können Nutzer miteinander interagieren oder Inhalte austauschen? **Ja** (Tropfen, Antworten, Chat).
- Teilt die App den genauen Standort mit anderen Nutzern? **Nein**.
- Ermöglicht die App den Kauf digitaler Waren? **Ja** (Mitgliedschaft als Abo).
- Moderation: Meldefunktion, Sperren und Blockieren vorhanden.

### Zielgruppe und Inhalte
- Altersgruppen: **16–17** und **18 und älter** (Oasis ist ab 16; unter 18 braucht eine Mitgliedschaft die Zustimmung
  der Eltern, siehe AGB).
- Die App richtet sich nicht an Kinder; der Store-Eintrag spricht keine Kinder an.

### Datensicherheit
Grundsätze (wie in der Datenschutzerklärung): keine Werbung, kein Tracking, keine Analyse-SDKs, kein Datenverkauf.
Alle Übertragungen sind verschlüsselt (HTTPS/TLS). Konto und Daten lassen sich in der App löschen
(Einstellungen → Konto löschen) und herunterladen (Daten-Export).

| Datentyp (Google) | Erhoben | Geteilt | Optional | Zweck |
|---|---|---|---|---|
| Name (Anzeigename) | ja | nein | nein | App-Funktionen, Kontoverwaltung |
| E-Mail-Adresse | ja | nein | nein | Kontoverwaltung, Kommunikation durch den Entwickler |
| Nutzer-IDs (Benutzername, Konto-ID) | ja | nein | nein | App-Funktionen, Kontoverwaltung |
| Sonstige Angaben: Geburtsdatum | ja | nein | nein | Kontoverwaltung (Mindestalter 16, Jugendschutz) |
| Fotos (Profilbild, Banner, Bilder in Tropfen) | ja | nein | ja | App-Funktionen |
| Sonstige nutzergenerierte Inhalte (Tropfen, Antworten, Oasen, Meldungen) | ja | nein | ja | App-Funktionen |
| Kaufverlauf (Mitgliedschaft) | ja | nein | ja | App-Funktionen, Kontoverwaltung |
| Geräte- oder andere IDs (Push-Token von FCM) | ja | nein | ja | App-Funktionen (Push-Hinweise) |

- **Nachrichten im Chat nicht angeben:** Sie sind Ende-zu-Ende-verschlüsselt, Oasis kann sie nicht lesen (Googles
  Ausnahme für Ende-zu-Ende-verschlüsselte Daten).
- **Nicht erhoben:** Standort, Kontakte, Kalender, Gesundheit, Zahlungsdaten (Karten/Konten), Browserverlauf, Analysen,
  Absturzberichte.
- **„Geteilt“ ist überall nein:** Dienstleister, die im Auftrag arbeiten (Firebase Cloud Messaging für die Zustellung,
  Mistral für die automatische Textprüfung, Mollie für Zahlungen im Web), zählen bei Google nicht als Weitergabe. Die
  Push-Nachricht selbst enthält nur eine zufällige Kennung, die Art und einen Link – Namen und Texte holt die App über
  die eigene Verbindung vom Oasis-Server.

### Weitere Erklärungen
- Behörden-App: nein. Finanzfunktionen: nein. Gesundheits-App: nein. Nachrichten-App (News): nein.
- Berechtigungen: `INTERNET`, `POST_NOTIFICATIONS` (Push-Hinweise, erst nach Zustimmung in den Hinweis-Einstellungen),
  Kamera nur über den System-Dialog „Foto aufnehmen“, Biometrie für das Entsperren des Chats.

## Version und Build
- `versionCode` = Commit-Zeit in UTC als `yyDDDHHmm` (Jahr, Tag im Jahr, Stunde, Minute), z. B. 262980815 für
  25.10.2026, 08:15 – jeder neue develop-Commit ist höher. (`yyMMddHHmm` passt nicht: 2610250815 liegt über Googles
  Grenze von 2 100 000 000.)
- `versionName` = 1.0.
- Store-Grafiken neu erzeugen: Feature-Grafik mit
  `./gradlew :app:testDebugUnitTest --tests '*StoreGraphicsTest*'` (→ `app/build/outputs/roborazzi/`), Screenshots aus
  dem Emulator mit dem Demokonto `mira`.
