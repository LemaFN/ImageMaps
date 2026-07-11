# ImageMap Plugin

Lädt ein Bild über eine eingebaute Weboberfläche hoch, zerlegt es in Minecraft-Map-Kacheln
und gibt dir die fertigen Karten ins Inventar – **persistent über Server-Neustarts hinweg**
(im Gegensatz zu ImageCanvas, das nach einem Neustart offenbar leere Karten zeigt, weil die
Bilddaten vermutlich nur im RAM gehalten wurden).

## Bauen

Voraussetzung: Java 21, Maven.

```bash
mvn clean package
```

Das fertige Jar liegt danach unter `target/ImageMapPlugin.jar`.

**Wichtig:** In `pom.xml` steht als Paper-Version `1.21.11-R0.1-SNAPSHOT`. Falls diese Version
noch nicht im PaperMC-Repository verfügbar ist (`https://repo.papermc.io/repository/maven-public/`),
trage dort die nächstliegende verfügbare 1.21.x-Version ein (z.B. `1.21.4-R0.1-SNAPSHOT`).
Das Plugin läuft dank `api-version: '1.21'` trotzdem auf deinem 1.21.11-Server.

Ich konnte den Build in meiner Sandbox nicht kompilieren/testen, da ich keinen Zugriff auf das
PaperMC-Repository habe. Bitte einmal lokal `mvn clean package` laufen lassen und mir eventuelle
Compile-Fehler zurückmelden – die behebe ich dann gezielt.

### Alternative: Automatisch mit GitHub Actions bauen

Im Repo liegt `.github/workflows/build.yml`. Sobald du das Projekt zu GitHub pushst, baut
GitHub Actions das Plugin automatisch bei jedem Push (Java 21 + Maven, PaperMC-Repo ist über
die `pom.xml` schon eingebunden). Das fertige Jar findest du danach:

1. Im Repo auf **Actions** klicken
2. Den neuesten Workflow-Lauf ("Build ImageMap Plugin") öffnen
3. Unten bei **Artifacts** liegt `ImageMapPlugin` zum Download (enthält `ImageMapPlugin.jar`)

Schlägt der Build fehl (z.B. weil die Paper-Version in `pom.xml` noch nicht existiert), zeigt
dir der Log in Actions genau die Fehlermeldung – schick sie mir, dann passe ich die `pom.xml`
gezielt an.

## Installation

1. `ImageMapPlugin.jar` in den `plugins/`-Ordner deines Paper-Servers legen
2. Server starten, `plugins/ImageMapPlugin/config.yml` öffnen
3. `public-host` auf deine echte öffentliche IP oder Domain setzen (die automatische Erkennung
   funktioniert hinter NAT/Portweiterreichung nicht zuverlässig!)
4. Sicherstellen, dass der Port aus `config.yml` (Standard `8853`) in deiner Firewall/deinem
   Router freigegeben ist – das ist ein **normaler HTTP-Port, unabhängig vom Minecraft-Port**
5. Server neu starten oder `/reload` (empfohlen: richtiger Neustart)

## Nutzung im Spiel

```
/imagemap upload
```
→ du bekommst einen klickbaren Link im Chat (15 Min gültig, konfigurierbar)

Auf der Webseite: Bild auswählen, optional Name, Breite/Höhe in Karten (z.B. 11×11),
optional Auto-Place aktivieren, absenden.

```
/imagemap confirm <CODE>
```
→ holt die fertigen Karten ab (du bekommst auch automatisch eine Chat-Nachricht mit
klickbarem Befehl, sobald der Upload verarbeitet wurde)

Ohne Auto-Place: Die Karten landen nummeriert (Spalte/Zeile in Name + Lore) im Inventar.
Baue die Item Frames selbst und setze die Karten von oben-links nach unten-rechts ein.

Mit Auto-Place: Schau beim Ausführen von `/imagemap confirm` auf eine **gerade, freie
vertikale Wand** – das Plugin versucht dann automatisch, Item Frames mit den Karten zu
platzieren. Funktioniert aktuell nur an Wänden (Nord/Süd/Ost/West-Flächen), nicht an
Boden/Decke. Schlägt die automatische Platzierung fehl, bekommst du die Karten stattdessen
ganz normal ins Inventar.

## Wie der Neustart-Bug vermieden wird

- Jedes hochgeladene Bild wird sofort in rohe 128×128-Palette-Bytes pro Kachel zerlegt und
  unter `plugins/ImageMapPlugin/jobs/<code>/tile_<spalte>_<zeile>.dat` gespeichert
- Für jede erzeugte Minecraft-Karte wird die Zuordnung `Map-ID → Kachel-Datei` in
  `plugins/ImageMapPlugin/maps.json` gespeichert
- Beim Plugin-Start (`onEnable`) wird diese Datei gelesen und für jede bekannte Map-ID der
  Renderer neu angehängt – die Karte zeigt danach wieder korrekt das Bild, auch wenn der
  Renderer (der nicht Teil der Vanilla-Weltdaten ist) beim Neustart eigentlich verloren gegangen wäre

## Bekannte Einschränkungen (v1)

- Kein HTTPS (nur HTTP) – für ein internes/Freundeskreis-Setup meist ausreichend
- Kein Schutz gegen Missbrauch des Upload-Tokens außer der 15-Minuten-Gültigkeit – wer den
  Link kennt, kann hochladen. Für einen öffentlichen Server ggf. zusätzlich absichern.
- Auto-Place unterstützt nur gerade vertikale Wände, keine Ecken/Treppen/Boden/Decke
- Bilder werden simpel per Nächste-Palettenfarbe konvertiert (kein Dithering) – für sehr
  farbverlaufsreiche Bilder etwas grobkörniger als z.B. professionelle Map-Art-Tools
