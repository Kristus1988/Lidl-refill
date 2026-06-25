# Lidl Connect Refill App

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher.png" alt="App Icon" width="120">
</p>

## 📱 Automatische Aufladung für Lidl Connect "on Demand" Tarife

Eine Android-App, die automatisch den "Refill aktivieren"-Button auf der Lidl Connect Webseite klickt, sobald das Datenvolumen unter 0,15 GB fällt.

---

## 🔒 **Datenschutz & Sicherheit – Alles läuft lokal auf deinem Handy!**

| Frage | Antwort |
|-------|---------|
| **Wo wird die Lidl-Seite aufgerufen?** | ✅ **Ausschließlich auf deinem Handy!** Die App verwendet den ChromeDriver, der lokal auf deinem Gerät einen unsichtbaren Chrome-Browser öffnet. |
| **Wird ein PC oder externer Server verwendet?** | ❌ **Nein!** Alles läuft lokal auf deinem Handy – kein PC, kein externer Server. |
| **Werden meine Daten an Dritte gesendet?** | ❌ **Nein!** Deine Zugangsdaten verlassen niemals dein Gerät. Sie werden verschlüsselt lokal gespeichert. |
| **Welche Internetverbindung wird genutzt?** | 📶 Die App nutzt deine **mobile Datenverbindung oder dein WLAN** – genau wie dein Browser. |
| **Ist die App sicher?** | ✅ **Ja!** Alle Aktionen finden lokal statt. Es gibt keine Cloud-Anbindung oder Datenübertragung nach außen. |

**Die App verhält sich genau so, als ob du die Lidl-Seite manuell im Browser öffnen würdest – nur automatisiert!**

---

## ✨ Features

- 🔐 **Verschlüsselte Passwort-Speicherung** (EncryptedSharedPreferences)
- 🎨 **Modernes Material Design** UI
- 🔄 **Automatischer Refill** im Hintergrund
- 📊 **Live-Status** mit aktuellen GB-Werten
- 🔴/🟢 **Berechtigungs-Check** mit farbigen Status
- ⚙️ **Anpassbare Einstellungen**
- 📱 **+49 Format-Konvertierung** für Telefonnummern
- 🎭 **Menschliches Verhalten** simuliert (Anti-Erkennung)
- 🧠 **Adaptive Prüf-Intervalle** (spart Akku)
- 📦 **Erkennung von Inklusiv-Volumen** (25 GB)
- 🚀 **Automatische Tarif-Erkennung** (S, M, L, XL)

---

## 🛠️ Technologien

- **Android SDK** (minSdk 21, targetSdk 34)
- **Java** (Sprache)
- **Selenium WebDriver** (Web-Automatisierung)
- **ChromeDriver** (Headless Browser auf dem Handy)
- **EncryptedSharedPreferences** (Sicherheit)
- **Material Design** (UI)

---

## 📦 Installation

### APK herunterladen
1. Gehe zu [Releases](https://github.com/deinusername/LidlRefillApp/releases)
2. Lade die neueste `app-debug.apk` herunter
3. Installiere die APK auf deinem Android-Gerät

### Von Quelle bauen
1. Repository klonen:
   ```bash
   git clone https://github.com/deinusername/LidlRefillApp.git
