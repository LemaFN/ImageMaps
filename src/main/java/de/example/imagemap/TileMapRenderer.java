package de.example.imagemap;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zeichnet einmalig 128x128 vorab berechnete Palette-Bytes auf die Karte.
 * Wird nur beim ersten Rendern pro MapView tatsächlich aktiv, danach übernimmt
 * Minecraft die Karte als "fertig gezeichnet" (der Client cached das Kartenbild selbst).
 */
public class TileMapRenderer extends MapRenderer {

    private final Path tileFile;
    private final Logger logger;
    private final Set<Integer> renderedViews = new HashSet<>();
    private byte[] cachedPixels;

    public TileMapRenderer(Path tileFile, Logger logger) {
        // contextual = false: die Karte sieht für jeden Spieler gleich aus
        super(false);
        this.tileFile = tileFile;
        this.logger = logger;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        int viewId = map.getId();
        if (renderedViews.contains(viewId)) {
            return;
        }

        byte[] pixels = getPixels();
        if (pixels == null) {
            return;
        }

        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, pixels[y * 128 + x]);
            }
        }
        renderedViews.add(viewId);
    }

    private byte[] getPixels() {
        if (cachedPixels != null) {
            return cachedPixels;
        }
        try {
            byte[] data = Files.readAllBytes(tileFile);
            if (data.length != 128 * 128) {
                logger.warning("Kachel-Datei hat falsche Größe: " + tileFile);
                return null;
            }
            cachedPixels = data;
            return cachedPixels;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Konnte Kachel-Datei nicht lesen: " + tileFile, e);
            return null;
        }
    }
}
