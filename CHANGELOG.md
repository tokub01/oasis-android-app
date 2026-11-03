# Changelog

<!-- Generated from resources/data/releases.json in the web repository by `php artisan oasis:releases changelog`. Do not edit by hand. -->

Alle Versionen von Oasis für Android. Eine Versionsnummer gilt für Web, iOS und Android zugleich; alle Versionen stehen auf https://get-oasis.com/neuigkeiten.

## Wie Oasis gebaut wird

Oasis wird von Tobias Kubina - VelWebSolutions entwickelt, mit Unterstützung von KI. Wie das genau aussieht, erzählen wir hier offen – und nur so weit, wie es sich aus der Versionsgeschichte der drei Projekte (Website, iOS-App, Android-App) belegen lässt.

27. Juni 2026: Das Projekt wird angelegt, ein leeres Grundgerüst. Ob dabei schon KI geholfen hat, lässt sich aus der Versionsgeschichte nicht belegen.

Ab 11. Oktober 2026: Oasis entsteht mit KI-Werkzeugen. Der Arbeitsplan von damals beschreibt die Aufteilung: Claude (Claude Code von Anthropic) schreibt für jeden Abschnitt die Vorgaben, baut Oberflächen und legt die Änderungen an. Codex (OpenAI) baut Teile der Server-Technik und prüft unter anderem die Datenschutzerklärung. Antigravity (Google) prüft Bedienung, Barrierefreiheit und die Verschlüsselung des Chats. Jedes Werkzeug prüft die Arbeit eines anderen und schreibt eigene, unabhängige Tests dagegen.

16. und 17. Oktober 2026: Hier werden Codex und Antigravity zum letzten Mal erwähnt, etwa bei der Prüfung der Zahlungen, der Echtzeit-Verbindung und der Privatsphäre-Einstellungen. Für den 18. bis 21. Oktober gibt es keinen Beleg, welches Werkzeug genutzt wurde.

Ab 22. Oktober 2026: Seitdem trägt jede Änderung in allen drei Projekten den Vermerk, dass sie gemeinsam mit Claude entstanden ist (Modell Claude Opus 5.5). Die iOS-App und die Android-App sind von Anfang an so entstanden. Seitdem wird Oasis also hauptsächlich mit Claude Code gebaut.

Was dabei gleich bleibt: Ein Mensch entscheidet, was in Oasis kommt, und gibt jede Änderung frei. Jede Änderung wird durch automatische Tests abgesichert, bevor sie ausgeliefert wird.

## 0.25.0 – 2026-11-04 – Klarere Tropfen, ruhigerer Chat

Antworten gibt es nur noch dort, wo sie erlaubt sind, Namen in jeder Schrift sehen richtig aus, du wählst die Farbe deiner Initialen, und der Chat schützt besser vor Schreien und Fluten.

- **Neu:** Farbe deiner Initialen: Ohne Profilbild wählst du in den Profileinstellungen eine Farbe – oder lässt „Automatisch“.
- **Neu:** Weitertragen geht jetzt auch ohne eigene Worte.
- **Neu:** Der Chat bittet ruhig um weniger Großbuchstaben und Wiederholungen und legt nach sehr vielen Nachrichten kurz eine Pause ein.
- **Verbessert:** Darf man auf einen Tropfen nicht antworten, steht dort statt „Antworten“ kurz, wer antworten darf. Weitertragen geht dann nur ohne eigenen Text.
- **Verbessert:** Die Kopfzeile jedes Tropfens ist überall gleich aufgebaut: Name, dann @Name · Oase · Zeit – ohne überflüssige Punkte.
- **Verbessert:** Ein beendeter Chat sagt jetzt, was passiert ist: wer ihn verlassen hat oder dass es das Konto nicht mehr gibt.
- **Verbessert:** Das Eingabefeld im Chat nennt immer, wie viele Zeichen hineinpassen, mit Tausenderpunkt („4.000“).
- **Verbessert:** Im Chat haben Nachrichten mehr Luft zum Rand, die Abstände sind überall gleich.
- **Behoben:** Am Ende einer Liste erscheint keine Fehlermeldung mehr, die Liste endet einfach.
- **Behoben:** Namen wie „Yıldız“ oder „İlkay Şahin“ sehen jetzt richtig aus, auch in den Initialen.

## 0.24.0 – 2026-11-03 – Ruhigerer Chat, ruhigerer Start

Der Chat ist ruhiger gestaltet und lässt sich mit Wischgesten bedienen, der Weg in Oasis ist kürzer, und Oasis ist ab jetzt ein Netzwerk für Erwachsene.

- **Neu:** Diese Versionshinweise: Bei jeder Version steht offen, was neu ist, was besser wurde und was behoben ist – und wie Oasis mit KI gebaut wird.
- **Neu:** Wischen zum Antworten, Stummschalten, Verlassen und Gelesen-Markieren; alles auch als Knopf.
- **Verbessert:** Ein ruhigerer Chat: Nachrichten gruppiert, die Zeit in der Blase, Tages-Trenner und „Neue Nachrichten“.
- **Verbessert:** Registrieren in kleinen Schritten mit einem vorgeschlagenen Benutzernamen; keine Pflicht-Einführung mehr.
- **Verbessert:** Den Chat startest du mit einem Knopf; eine leere Chat-Liste schlägt dir Menschen vor.
- **Verbessert:** Oasis ist für Erwachsene: Registrieren geht ab 18 Jahren.
- **Behoben:** Im Eingabebereich überlappt nichts mehr, Zeichengrenze und Zeiten sind klar lesbar.
- **Entfernt:** Abstimmungen unter Mitgliedern. Gehört wirst du jetzt über „Ideen für Oasis“.

## 0.23.0 – 2026-11-03 – Ruhe, Treffen und Erinnerungen

Ein Ruhemodus, Umfragen und Treffen in Oasen, gemeinsame Sammlungen, ein Wochenbrief, ein dunkles Design und eine Karte der Oasen.

- **Neu:** Ruhemodus für 1 Stunde, 8 Stunden, bis 7 Uhr oder bis du ihn ausschaltest.
- **Neu:** „Du hast alles Neue gesehen“: Die Quelle sagt, wann du durch bist.
- **Neu:** „Heute vor einem Jahr“ und „Deine Zeit in Oasis“, gezählt nur auf deinem Gerät.
- **Neu:** Umfragen in Tropfen, Treffen in Oasen und Paten, die neue Mitglieder begrüßen.
- **Neu:** Gemeinsame Sammlungen in Oasen und gemeinsame Zeitkapseln.
- **Neu:** Der Oasen-Wochenbrief am Sonntagabend und „Ideen für Oasis“.
- **Neu:** „Wohin dein Beitrag geht“: echte Zahlen und Finanzberichte, sobald sie veröffentlicht sind.
- **Neu:** Dunkles Design und eine Karte der Oasen nach Bundesland.

## 0.22.0 – 2026-11-03 – Reaktionen, Antworten und Umfragen im Chat

Der Chat wird lebendiger: eigene Hintergründe, Effekte, Reaktionen, Antworten, Umfragen und eine angeheftete Nachricht.

- **Neu:** Persönliche Chat-Hintergründe und Nachrichten mit Effekt, etwa Konfetti oder Herzen.
- **Neu:** Auf Nachrichten reagieren und direkt antworten.
- **Neu:** Eine angeheftete Nachricht und Umfragen im Chat.

## 0.21.0 – 2026-11-02 – Leichter beitreten, besser sprechen

Vier klare Schritte zur Mitgliedschaft, ein neuer Eingabebereich im Chat und bessere Sprachnachrichten.

- **Neu:** Vier Schritte zur Mitgliedschaft: Konto, E-Mail-Adresse, Handynummer, Beitrag.
- **Neu:** Sprachnachrichten mit Abspielen und Pausieren, Vorspulen, Zeit und Geschwindigkeit.
- **Neu:** Ein Tipp auf ein Profilbild im Chat öffnet das Profil.
- **Neu:** Sprachnachrichten als Text, erkannt nur auf deinem Gerät.
- **Verbessert:** Der Eingabebereich im Chat: ein Knopf für Anhänge, ein breites Feld, ein Knopf für Aufnehmen und Senden.
- **Verbessert:** Push-Mitteilungen für Chats sind von Anfang an eingeschaltet.
- **Sicherheit:** Weitere Schwachstellen wurden behoben, unter anderem bei Chat-Anhängen und Zahlungen.

## 0.20.1 – 2026-10-29 – Gründliche Code-Prüfung

Eine vollständige Prüfung des Codes auf Sicherheit und Fehler, mit einem Test für jeden Fund.

- **Behoben:** Kleinere Fehler, die die Prüfung gefunden hat.
- **Sicherheit:** Schwachstellen aus derselben Prüfung wurden auch in der Android-App behoben.

## 0.20.0 – 2026-10-28 – Zeitkapseln, Jahrbuch und Anhänge im Chat

Die Mitgliedsstufen bekommen echte Vorteile, und der Chat kann Fotos, Sprachnachrichten, Videos und Dateien – verschlüsselt.

- **Neu:** Zeitkapseln, ein druckbares Jahrbuch, Begleitplätze und ein Unterstützer-Zeichen, je nach Mitgliedsstufe.
- **Neu:** Ende-zu-Ende-verschlüsselte Fotos, Sprachnachrichten, kurze Videos und Dateien im Chat, auch zum einmaligen Ansehen.
- **Neu:** Profilfotos im Chat und eine kurze Statuszeile unter deinem Namen.
- **Neu:** Aus anderen Apps direkt in Oasis teilen.

## 0.19.0 – 2026-10-27 – Ein Preis überall

Gleiche Preise im Browser, im App Store und bei Google Play, und die ersten offiziellen Oasen.

- **Neu:** Bestätigungs- und Code-Links von der Website öffnen sich in der App.
- **Verbessert:** Eine Preisliste für Browser, App Store und Google Play: Du zahlst überall gleich viel.

## 0.18.0 – 2026-10-26 – Nur für Mitglieder, Anmelden ohne Passwort

Oasis ist jetzt ganz den Mitgliedern vorbehalten, und du meldest dich mit Code oder Passkey an.

- **Neu:** Anmelden mit einem Code per E-Mail oder SMS oder mit einem Passkey.
- **Neu:** Deine Handynummer wird per SMS bestätigt, bevor du bezahlst und Oasis nutzt.
- **Neu:** Links aus E-Mails öffnen direkt die App, wenn sie installiert ist.
- **Neu:** Mitglied werden direkt in der App über Google Play.
- **Neu:** Push-Mitteilungen über Firebase; ein eigener Kanal „Chats“.
- **Verbessert:** Oasis ist nur für Mitglieder: Nichts ist mehr öffentlich, nichts landet in Suchmaschinen.
- **Entfernt:** Die kostenlose Probezeit und der Nur-Lesen-Modus.

## 0.17.0 – 2026-10-25 – Oasis für Android

Die erste Version der Android-App – und ein Code statt Link zum Bestätigen der E-Mail-Adresse.

- **Neu:** Deine E-Mail-Adresse bestätigst du auch mit einem sechsstelligen Code.
- **Neu:** Die Android-App mit Quelle, Tropfen, Oasen, Profilen und Suche.
- **Neu:** Der Ende-zu-Ende-verschlüsselte Chat, kompatibel mit Browser und iOS.
- **Neu:** Mitgliedschaft mit Rechnungen, Einstellungen, Privatsphäre und Hinweisen.
- **Neu:** Moderation und Verwaltung für das Team.
- **Verbessert:** Häkchen und Schalter zeigen deutlich, ob sie an oder aus sind.
- **Verbessert:** Namen werden bei der Anmeldung geprüft wie Tropfen.
- **Verbessert:** Bedienung, wie man sie von Android kennt.
