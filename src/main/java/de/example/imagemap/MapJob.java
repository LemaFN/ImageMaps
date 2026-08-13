package de.example.imagemap;

import java.util.UUID;

/**
 * Ein fertig verarbeiteter Bild-Upload, wartet darauf per /imagemap confirm <code>
 * im Spiel abgeholt zu werden. Die eigentlichen Pixel-Daten liegen als Rohdateien
 * (tile_<col>_<row>.dat) im Job-Ordner, damit ein Server-Neustart nichts zerstört.
 */
public class MapJob {

    public String code;
    public UUID ownerId;
    public String ownerName;
    public String imageName;
    public int width;   // Anzahl Karten horizontal
    public int height;  // Anzahl Karten vertikal
    public boolean autoPlace;
    public long createdAt;
    public long expiresAt;
    public boolean consumed;

    public MapJob() {
    }

    public MapJob(String code, UUID ownerId, String ownerName, String imageName,
                  int width, int height, boolean autoPlace, long createdAt, long expiresAt) {
        this.code = code;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.imageName = imageName;
        this.width = width;
        this.height = height;
        this.autoPlace = autoPlace;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.consumed = false;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
