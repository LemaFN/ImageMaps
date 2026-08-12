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
            throw new IOException("Bilddatei konnte nicht gelesen werden (nicht unterstütztes Format?) - "
                    + imageBytes.length + " Bytes empfangen, erkannte Signatur: " + detectSignature(imageBytes));
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

    /**
     * Erkennt anhand der ersten Bytes, ob die Datei überhaupt wie ein bekanntes Bildformat
     * aussieht. Hilft bei der Fehlerdiagnose zu unterscheiden zwischen "Datei ist einfach
     * kein unterstütztes Format" und "Datei wurde beim Upload/Parsing beschädigt/abgeschnitten".
     */
    private static String detectSignature(byte[] data) {
        if (data == null || data.length < 4) return "zu kurz für Signaturerkennung";
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "PNG-Signatur korrekt erkannt (89 50 4E 47) - Datei sollte eigentlich lesbar sein, evtl. abgeschnitten/beschädigt";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "JPEG-Signatur erkannt";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "GIF-Signatur erkannt";
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return "BMP-Signatur erkannt";
        }
        StringBuilder sb = new StringBuilder("unbekannte Signatur, erste Bytes: ");
        for (int i = 0; i < Math.min(8, data.length); i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString().trim();
    }
}
