package de.example.imagemap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler multipart/form-data Parser, damit keine externe HTTP-Library nötig ist.
 * Reicht für einfache Formulare mit Textfeldern + einer Datei.
 */
public class MultipartParser {

    public static class Part {
        public String name;
        public String filename; // null wenn kein Datei-Feld
        public byte[] data;

        public String asString() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    public static List<Part> parse(byte[] body, String boundary) {
        List<Part> parts = new ArrayList<>();
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        List<Integer> positions = findAll(body, boundaryBytes);
        for (int i = 0; i < positions.size() - 1; i++) {
            int start = positions.get(i) + boundaryBytes.length;
            int end = positions.get(i + 1);
            if (start >= end) continue;

            // Überspringe CRLF nach der boundary
            if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') {
                start += 2;
            }
            if (end - 2 >= start && body[end - 2] == '\r' && body[end - 1] == '\n') {
                end -= 2;
            }
            if (start >= end) continue;

            byte[] section = new byte[end - start];
            System.arraycopy(body, start, section, 0, section.length);

            int headerEnd = indexOf(section, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), 0);
            if (headerEnd < 0) continue;

            String headerText = new String(section, 0, headerEnd, StandardCharsets.UTF_8);
            byte[] content = new byte[section.length - headerEnd - 4];
            System.arraycopy(section, headerEnd + 4, content, 0, content.length);

            Part part = new Part();
            part.data = content;

            for (String line : headerText.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-disposition")) {
                    Map<String, String> attrs = parseDisposition(line);
                    part.name = attrs.get("name");
                    part.filename = attrs.get("filename");
                }
            }

            if (part.name != null) {
                parts.add(part);
            }
        }
        return parts;
    }

    public static String extractBoundary(String contentType) {
        if (contentType == null) return null;
        for (String piece : contentType.split(";")) {
            piece = piece.trim();
            if (piece.startsWith("boundary=")) {
                String b = piece.substring("boundary=".length());
                if (b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static Map<String, String> parseDisposition(String line) {
        Map<String, String> result = new HashMap<>();
        String[] pieces = line.split(";");
        for (String piece : pieces) {
            piece = piece.trim();
            int eq = piece.indexOf('=');
            if (eq > 0) {
                String key = piece.substring(0, eq).trim();
                String value = piece.substring(eq + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
        return result;
    }

    private static List<Integer> findAll(byte[] data, byte[] pattern) {
        List<Integer> result = new ArrayList<>();
        int idx = 0;
        while (true) {
            int found = indexOf(data, pattern, idx);
            if (found < 0) break;
            result.add(found);
            idx = found + pattern.length;
        }
        return result;
    }

    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
