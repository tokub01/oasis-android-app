# Mildcrowd für Android

Die native Android-App von [Mildcrowd](https://mildcrowd.com) – werbefrei, chronologisch. Sie baut die iOS-App
(`oasis-ios`) 1:1 nach: gleiche Screens, Abläufe, Texte und Design-Tokens, gegen dieselbe Laravel-API (`/api/v1`).

## Aufbau

| Modul | Inhalt (iOS-Gegenstück) |
| --- | --- |
| `:core` | API-Client (Retrofit/OkHttp, kotlinx.serialization), Fehler, Session, Token im Android Keystore, Formate (OasisCore) |
| `:core-testing` | `StubTransport` und Test-Helfer für alle Module |
| `:design` | Farben, Schriften (Bricolage Grotesque, Manrope), Komponenten, Routen, Navigator, Deep Links (OasisDesign) |
| `:chat-crypto` | Ende-zu-Ende-Krypto, byte-kompatibel mit Web und iOS (OasisChatCrypto) |
| `:realtime` | Reverb-Client (Pusher-Protokoll) (OasisRealtime) |
| `:feature:*` | network, chat, account, live, membership, staff – je API, Repository, ViewModels, Screens |
| `:app` | Activity, Hilt-Graph, Tab-Leiste, Kopfzeile, Sheets, Zuordnung Route → Screen (OasisAppUI) |

Schichten: Compose-UI → ViewModel (Hilt, `StateFlow`) → Repository-Interface → Retrofit-Service → API.
Feature-Module kennen sich nicht; sie treffen sich nur über die Routen in `:design`.

Begriffe und Abkürzungen: [docs/glossary.md](docs/glossary.md).

## Bauen und starten

Voraussetzungen: JDK 21, Android SDK (Platform 37, Build-Tools), ein Emulator (API 35/36).

```bash
./gradlew :app:installDebug
```

- **debug** spricht mit dem lokalen Sail-Backend auf dem Mac (`http://10.0.2.2`, Reverb ebenfalls über `10.0.2.2`).
  Für ein echtes Gerät die LAN-Adresse in `secrets.properties` setzen (siehe `secrets.properties.example`).
- **staging** spricht mit `staging.mildcrowd.com`; das Gate-Passwort kommt aus `secrets.properties` oder der
  Umgebungsvariable `OASIS_GATE_KEY` und wird nie committet.
- **release** spricht mit `mildcrowd.com`.

## Prüfen

```bash
./gradlew ktlintCheck detekt :app:lintDebug test
./gradlew connectedDebugAndroidTest
```

`test` enthält die Unit-Tests und die Compose-UI-Tests unter Robolectric; `connectedDebugAndroidTest` läuft auf
einem Emulator. Die CI (`.github/workflows/ci.yml`) macht beides.

## Push und Google Play

- **Push (FCM):** Das Backend stellt Android-Hinweise über Firebase Cloud Messaging zu (`App\Support\Notifications\Fcm`,
  Dienstkonto `OASIS_FCM_CREDENTIALS`). Der Build bindet Firebase nur ein, wenn `app/google-services.json` liegt (lokal
  kopiert, in CI aus dem Secret `GOOGLE_SERVICES_JSON_B64`); ohne die Datei baut die App ohne Push.
- **Google Play Billing:** Die App verkauft die Mitgliedschaft als Play-Abo (gleiche Stufen und Preise wie Web und iOS,
  `PlayProducts.kt`). Der Server prüft und bestätigt jeden Kauf bei Google (`GooglePurchaseController`,
  `docs/oasis-membership.md` „Google Play“) – nur, solange dort `OASIS_GOOGLE_PLAY` an ist; sonst zeigt der Checkout,
  dass Mitgliedschaften in der App gerade nicht abgeschlossen werden können.
