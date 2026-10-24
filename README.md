# Oasis für Android

Die native Android-App von [Oasis](https://get-oasis.com) – werbefrei, chronologisch. Sie baut die iOS-App
(`oasis-ios`) 1:1 nach: gleiche Screens, Abläufe, Texte und Design-Tokens, gegen dieselbe Laravel-API (`/api/v1`).

## Aufbau

| Modul | Inhalt (iOS-Gegenstück) |
| --- | --- |
| `:core` | API-Client (Retrofit/OkHttp, kotlinx.serialization), Fehler, Session, Token im Android Keystore, Formate (OasisCore) |
| `:core-testing` | `StubTransport` und Test-Helfer für alle Module |
| `:design` | Farben, Schriften (Fraunces, Manrope), Komponenten, Routen, Navigator, Deep Links (OasisDesign) |
| `:chat-crypto` | Ende-zu-Ende-Krypto, byte-kompatibel mit Web und iOS (OasisChatCrypto) |
| `:realtime` | Reverb-Client (Pusher-Protokoll) (OasisRealtime) |
| `:feature:*` | network, chat, account, live, membership, staff – je API, Repository, ViewModels, Screens |
| `:app` | Activity, Hilt-Graph, Tab-Leiste, Kopfzeile, Sheets, Zuordnung Route → Screen (OasisAppUI) |

Schichten: Compose-UI → ViewModel (Hilt, `StateFlow`) → Repository-Interface → Retrofit-Service → API.
Feature-Module kennen sich nicht; sie treffen sich nur über die Routen in `:design`.

## Bauen und starten

Voraussetzungen: JDK 21, Android SDK (Platform 37, Build-Tools), ein Emulator (API 35/36).

```bash
./gradlew :app:installDebug
```

- **debug** spricht mit dem lokalen Sail-Backend auf dem Mac (`http://10.0.2.2`, Reverb ebenfalls über `10.0.2.2`).
  Für ein echtes Gerät die LAN-Adresse in `secrets.properties` setzen (siehe `secrets.properties.example`).
- **staging** spricht mit `staging.get-oasis.com`; das Gate-Passwort kommt aus `secrets.properties` oder der
  Umgebungsvariable `OASIS_GATE_KEY` und wird nie committet.
- **release** spricht mit `get-oasis.com`.

## Prüfen

```bash
./gradlew ktlintCheck detekt :app:lintDebug test
./gradlew connectedDebugAndroidTest
```

`test` enthält die Unit-Tests und die Compose-UI-Tests unter Robolectric; `connectedDebugAndroidTest` läuft auf
einem Emulator. Die CI (`.github/workflows/ci.yml`) macht beides.

## Offene Punkte

- **Push (FCM)** ist vorbereitet, braucht aber ein Firebase-Projekt (`app/google-services.json`) und Backend-Unterstützung
  für Android-Geräte; das Backend kann heute APNs und Web Push.
- **Google Play Billing** ist vorbereitet (gleiche Stufen und Preise wie iOS); die Serverprüfung von Play-Käufen fehlt
  im Backend noch.
