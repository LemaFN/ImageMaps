package de.example.imagemap;

/**
 * Verknüpft eine persistente Minecraft-Map-ID (die, die auch im Item steckt) mit der
 * Rohdaten-Datei auf der Festplatte. Wird in maps.json gespeichert und beim
 * Server-/Plugin-Neustart genutzt, um den Renderer wieder an die Map zu hängen.
 * Das ist der Kern-Fix gegenüber ImageCanvas, das nach einem Neustart leere Karten zeigte.
 */
public class MapTileRef {

    public int mapId;
    public String jobCode;
    public int col;
    public int row;
    public String tileFile; // absoluter Pfad zur .dat Datei mit 128*128 Palette-Bytes
    public String worldName;

    public MapTileRef() {
    }

    public MapTileRef(int mapId, String jobCode, int col, int row, String tileFile, String worldName) {
        this.mapId = mapId;
        this.jobCode = jobCode;
        this.col = col;
        this.row = row;
        this.tileFile = tileFile;
        this.worldName = worldName;
    }
}
