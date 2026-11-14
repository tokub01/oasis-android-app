# Mildcrowd in der Google Play Console

Vorlage für den internen Test – das Gegenstück zu TestFlight. Die App für Play ist die Build-Variante `play`:
Paketname `de.velwebsolutions.mildcrowd`, spricht mit `staging.mildcrowd.com` (mit Staging-Gate) wie der TestFlight-Build,
mit R8 verkleinert und mit dem Upload-Schlüssel signiert. Google signiert sie für die Geräte neu (Play App Signing).

## Was du selbst tun musst (Schritt für Schritt)

1. **Entwicklerkonto** unter <https://play.google.com/console> anlegen (einmalig 25 US-$; Kontotyp „Organisation“
   oder „Privatperson“, Identitätsprüfung durch Google).
2. **App anlegen:** „App erstellen“ → Name „Mildcrowd“, Standardsprache Deutsch (Deutschland), App (nicht Spiel),
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
     Mildcrowd mit „Releases für Testtracks verwalten“ (und „Store-Eintrag verwalten“, wenn die Texte mit hochgehen sollen).
8. **GitHub-Secrets** im Repo `tokub01/mildcrowd-android-app` (Settings → Secrets and variables → Actions):

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
`https://mildcrowd.com/datenschutz`

### App-Zugriff (für die Prüfung durch Google)
„Alle oder einige Funktionen sind eingeschränkt“ → Anleitung:

> Mildcrowd ist ein soziales Netzwerk; alle Funktionen setzen ein Konto voraus. Bitte mit dem Demokonto anmelden:
> Benutzername `mira`, Passwort siehe Feld „Passwort“. Danach sind Start, Entdecken, Crowds, Profil und Einstellungen
> sichtbar. Der Chat ist Ende-zu-Ende-verschlüsselt; zum Ansehen in „Chats“ die Chat-Passphrase eingeben.

Das Passwort des Demokontos und die Chat-Passphrase gehören nur in die Play Console, nicht in dieses Repository. Sie
stehen in den Testdaten des Laravel-Projekts (`database/seeders/DemoContentSeeder.php`) bzw. im iOS-UI-Test
(`UITests/ChatUITests.swift`). Voraussetzung: Das Demokonto `mira` existiert auf `staging.mildcrowd.com`.

### Anzeigen
„Nein, meine App enthält keine Werbung.“

### Einstufung des Inhalts (IARC-Fragebogen)
- Kategorie: **Soziale Netzwerke, Foren, nutzergenerierte Inhalte und Kommunikation**.
- Gewalt, Sexualität, Sprache, kontrollierte Substanzen, Glücksspiel: **Nein** (Mildcrowd selbst zeigt solche Inhalte nicht;
  Nutzerinhalte werden moderiert).
- Können Nutzer miteinander interagieren oder Inhalte austauschen? **Ja** (Posts, Antworten, Chat).
- Teilt die App den genauen Standort mit anderen Nutzern? **Nein**.
- Ermöglicht die App den Kauf digitaler Waren? **Ja** (Mitgliedschaft als Abo).
- Moderation: Meldefunktion, Sperren und Blockieren vorhanden.

### Zielgruppe und Inhalte
- Altersgruppen: nur **18 und älter** (Mildcrowd ist ab 18, AGB § 3; keine Altersgruppe unter 18 ankreuzen).
- Die App richtet sich nicht an Kinder; der Store-Eintrag spricht keine Kinder an.

### Datensicherheit
Grundsätze (wie in der Datenschutzerklärung): keine Werbung, kein Tracking, keine Analyse-SDKs, kein Datenverkauf.
Alle Übertragungen sind verschlüsselt (HTTPS/TLS). Konto und Daten lassen sich in der App löschen
(Einstellungen → Konto löschen) und herunterladen (Daten-Export).

| Datentyp (Google) | Erhoben | Geteilt | Optional | Zweck |
|---|---|---|---|---|
| Name (Anzeigename) | ja | nein | nein | App-Funktionen, Kontoverwaltung |
| E-Mail-Adresse | ja | nein | nein | Kontoverwaltung, Kommunikation durch den Entwickler |
| Telefonnummer (Handynummer) | ja | nein | nein | Kontoverwaltung (Bestätigung per SMS-Code, Schutz vor Zweitkonten und Bots); der SMS-Dienstleister seven.io erhält sie nur für den Code, kein anderes Mitglied sieht sie |
| Nutzer-IDs (Benutzername, Konto-ID) | ja | nein | nein | App-Funktionen, Kontoverwaltung |
| Sonstige Angaben: Geburtsdatum | ja | nein | nein | Kontoverwaltung (Mindestalter 18) |
| Fotos (Profilbild, Banner, Bilder in Posts; Fotos aus einer gemeldeten Chat-Nachricht) | ja | nein | ja | App-Funktionen; Sicherheit und Moderation (Meldung) |
| Videos (nur aus einer gemeldeten Chat-Nachricht) | ja | nein | ja | Sicherheit und Moderation (Meldung) |
| Audiodateien (Sprachnachrichten, nur aus einer gemeldeten Chat-Nachricht) | ja | nein | ja | Sicherheit und Moderation (Meldung) |
| Dateien und Dokumente (nur aus einer gemeldeten Chat-Nachricht) | ja | nein | ja | Sicherheit und Moderation (Meldung) |
| Sonstige nutzergenerierte Inhalte (Posts, Antworten, Crowds, Meldungen, gemeldete Chat-Nachricht) | ja | nein | ja | App-Funktionen; Sicherheit und Moderation (Meldung) |
| Kaufverlauf (Mitgliedschaft) | ja | nein | ja | App-Funktionen, Kontoverwaltung |
| Geräte- oder andere IDs (Push-Token von FCM) | ja | nein | ja | App-Funktionen (Push-Hinweise; nur nach Zustimmung, siehe unten) |

- **Nachrichten im Chat nicht angeben:** Sie sind Ende-zu-Ende-verschlüsselt, Mildcrowd kann sie nicht lesen (Googles
  Ausnahme für Ende-zu-Ende-verschlüsselte Daten). Das gilt auch für Sprachnachrichten: Aufnahme und Wiedergabe laufen
  verschlüsselt, und „Als Text anzeigen“ erkennt die Sprache ausschließlich auf dem Gerät (`VoiceTranscription.kt`:
  nur `createOnDeviceSpeechRecognizer`, PCM nur im Arbeitsspeicher).
- **Ausnahme Meldung (deshalb Fotos, Videos, Audiodateien, Dateien und Dokumente oben als erhoben):** Meldet ein Mitglied
  eine Chat-Nachricht, gehen genau diese eine Nachricht und ihre Anhänge unverschlüsselt zur Prüfung an den Server
  (`feature/chat/…/ui/ReportModel.kt` `attachmentNote`, `ReportSheet.kt`; Datenschutzerklärung Abschnitt 14/15). Das
  ist optional (nur auf Wunsch des Mitglieds), Nutzerinhalt, wird nicht geteilt und verschlüsselt übertragen (TLS).
  „Löschung anfordern“: ja, mit der Einschränkung, die die Datenschutzerklärung nennt – die Kopie beim Moderationsfall
  bleibt als Nachweis für `records_years` Jahre nach Abschluss des Falls und wird dann automatisch gelöscht (Art. 6
  Abs. 1 lit. c DSGVO, Art. 16 DSA). Alle anderen Chat-Nachrichten bleiben unter Googles E2E-Ausnahme.
- **Nicht erhoben:** Standort, Kontakte, Kalender, Gesundheit, Zahlungsdaten (Karten/Konten), Browserverlauf, Analysen,
  Absturzberichte.
- **„Geteilt“ ist überall nein:** Dienstleister, die im Auftrag arbeiten (Firebase Cloud Messaging für die Zustellung,
  seven.io für SMS-Codes, Mistral für die automatische Textprüfung, Mollie für Zahlungen im Web), zählen bei Google
  nicht als Weitergabe.
- **Push-Nachricht:** Sie enthält nur einen allgemeinen Text („Es gibt Neues.“), den Zähler für das App-Symbol, die
  Zieladresse in der App und eine zufällige Kennung – keine Namen und keine Texte. Mit der Kennung holt die App den
  Hinweis selbst über die eigene Verbindung vom Mildcrowd-Server und baut die Benachrichtigung auf dem Gerät.

### Weitere Erklärungen
- Behörden-App: nein. Finanzfunktionen: nein. Gesundheits-App: nein. Nachrichten-App (News): nein.
- Berechtigungen (gemergtes Manifest aus `app/src/main/AndroidManifest.xml` und `feature/chat/src/main/AndroidManifest.xml`):
  - `INTERNET`.
  - `POST_NOTIFICATIONS` (Push-Hinweise: der Systemdialog wird direkt nach der Anmeldung erfragt, ab Android 13; auf
    älteren Geräten ohne diesen Dialog fragt die App stattdessen selbst „Push-Hinweise einschalten?“ mit Ja/Später und
    registriert das Gerät erst nach einem Ja; „Später“ merkt sie sich, einschalten geht dann in den Hinweis-Einstellungen).
  - `RECORD_AUDIO` (Sprachnachrichten im Chat: das Mikrofon läuft nur, während das Mitglied eine Sprachnachricht
    aufnimmt, nach einer Erklärung im Laufzeit-Dialog; die Aufnahme wird auf dem Gerät Ende-zu-Ende verschlüsselt, Mildcrowd
    kann sie nicht hören. `android.hardware.microphone` ist `required="false"`: ohne Mikrofon bleibt der übrige Chat).
    Im Datensicherheits-Formular deshalb kein „Audio“ als erhoben angeben – außer über die Meldung, siehe oben.
  - `USE_BIOMETRIC` (Entsperren des Chats mit Fingerabdruck oder Gesicht über `BiometricPrompt`: ein biometrisch
    geschützter Schlüssel im Android Keystore öffnet ein zufälliges Geheimnis; keine biometrischen Daten verlassen das
    Gerät, Mildcrowd erhält keine).
  - Keine Kamera-Berechtigung: „Foto aufnehmen“ nutzt den System-Dialog (`TakePicture`), der die Kamera-App öffnet.

## Version und Build
- `versionCode` = Commit-Zeit in UTC als `yyDDDHHmm` (Jahr, Tag im Jahr, Stunde, Minute), z. B. 262980815 für
  25.10.2026, 08:15 – jeder neue develop-Commit ist höher. (`yyMMddHHmm` passt nicht: 2610250815 liegt über Googles
  Grenze von 2 100 000 000.)
- `versionName` = die gemeinsame Mildcrowd-Version aus `app/build.gradle.kts` (z. B. 0.27.0); Web, iOS und Android tragen
  dieselbe Nummer (`CHANGELOG.md`).
- Store-Grafiken neu erzeugen: Feature-Grafik mit
  `./gradlew :app:testDebugUnitTest --tests '*StoreGraphicsTest*'` (→ `app/build/outputs/roborazzi/`), Screenshots aus
  dem Emulator mit dem Demokonto `mira`.
