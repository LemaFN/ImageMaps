package de.example.imagemap;

import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public class ImageProcessor {

    /**
     * Skaliert das Bild auf width*128 x height*128 Pixel, schneidet es in width*height
     * Kacheln und schreibt jede Kachel als rohe Palette-Byte-Datei (128*128 Bytes) in
     * den angegebenen Zielordner. Dateiname: tile_<col>_<row>.dat
     */
    @SuppressWarnings("deprecation")
    public static void processAndSaveTiles(byte[] imageBytes, int width, int height, Path targetDir) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (original == null) {
            throw new IOException("Bilddatei konnte nicht gelesen werden (nicht unterstütztes Format?)");
        }

        int targetW = width * 128;
        int targetH = height * 128;

        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, targetW, targetH, null);
        g.dispose();

        Files.createDirectories(targetDir);

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                byte[] tile = new byte[128 * 128];
                int offsetX = col * 128;
                int offsetY = row * 128;

                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        int argb = scaled.getRGB(offsetX + x, offsetY + y);
                        int alpha = (argb >>> 24) & 0xFF;
                        byte paletteByte;
                        if (alpha < 64) {
                            paletteByte = 0; // transparent
                        } else {
                            int r = (argb >> 16) & 0xFF;
                            int gg = (argb >> 8) & 0xFF;
                            int b = argb & 0xFF;
                            paletteByte = MapPalette.matchColor(new Color(r, gg, b));
                        }
                        tile[y * 128 + x] = paletteByte;
                    }
                }

                Path tileFile = targetDir.resolve("tile_" + col + "_" + row + ".dat");
                Files.write(tileFile, tile);
            }
        }
    }
}
