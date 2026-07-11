package de.example.imagemap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class UploadServer {

    private final ImageMapPlugin plugin;
    private HttpServer server;

    public UploadServer(ImageMapPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/upload", new UploadHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if (method.equalsIgnoreCase("GET")) {
                    handleGet(exchange, path);
                } else if (method.equalsIgnoreCase("POST")) {
                    handlePost(exchange);
                } else {
                    respond(exchange, 405, "text/plain", "Method not allowed");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Fehler im Upload-Server", e);
                try {
                    respond(exchange, 500, "text/plain", "Interner Fehler: " + e.getMessage());
                } catch (IOException ignored) {
                }
            }
        }

        private void handleGet(HttpExchange exchange, String path) throws IOException {
            // erwartet /upload/<token>
            String[] segments = path.split("/");
            String token = segments.length >= 3 ? segments[2] : null;

            if (token == null || plugin.getJobStore().getPendingUpload(token) == null) {
                respond(exchange, 404, "text/html; charset=utf-8", htmlPage(
                        "Link ungültig",
                        "<p>Dieser Upload-Link ist ungültig oder abgelaufen (15 Minuten Gültigkeit).</p>" +
                        "<p>Gib im Spiel erneut <code>/imagemap upload</code> ein.</p>"));
                return;
            }

            respond(exchange, 200, "text/html; charset=utf-8", uploadFormHtml(token));
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String boundary = MultipartParser.extractBoundary(contentType);
            if (boundary == null) {
                respond(exchange, 400, "text/plain", "Ungültiger Content-Type");
                return;
            }

            int maxBytes = plugin.getConfig().getInt("max-upload-mb", 20) * 1024 * 1024;
            byte[] body = readAllLimited(exchange.getRequestBody(), maxBytes);
            if (body == null) {
                respond(exchange, 413, "text/html; charset=utf-8", htmlPage("Datei zu groß",
                        "<p>Die Datei überschreitet das erlaubte Limit.</p>"));
                return;
            }

            List<MultipartParser.Part> parts = MultipartParser.parse(body, boundary);

            String token = null, name = null, widthStr = null, heightStr = null, autoplace = null;
            byte[] imageBytes = null;

            for (MultipartParser.Part part : parts) {
                switch (part.name) {
                    case "token" -> token = part.asString().trim();
                    case "name" -> name = part.asString().trim();
                    case "width" -> widthStr = part.asString().trim();
                    case "height" -> heightStr = part.asString().trim();
                    case "autoplace" -> autoplace = part.asString().trim();
                    case "file" -> imageBytes = part.data;
                    default -> {}
                }
            }

            PendingUpload pending = token != null ? plugin.getJobStore().getPendingUpload(token) : null;
            if (pending == null) {
                respond(exchange, 400, "text/html; charset=utf-8", htmlPage("Link abgelaufen",
                        "<p>Der Upload-Link ist abgelaufen. Gib im Spiel erneut <code>/imagemap upload</code> ein.</p>"));
                return;
            }

            if (imageBytes == null || imageBytes.length == 0) {
                respond(exchange, 400, "text/html; charset=utf-8", htmlPage("Kein Bild",
                        "<p>Es wurde keine Bilddatei übermittelt.</p>"));
                return;
            }

            int width, height;
            int maxGrid = plugin.getConfig().getInt("max-grid-size", 25);
            try {
                width = Integer.parseInt(widthStr);
                height = Integer.parseInt(heightStr);
            } catch (Exception e) {
                respond(exchange, 400, "text/html; charset=utf-8", htmlPage("Ungültige Größe",
                        "<p>Breite/Höhe müssen Zahlen sein.</p>"));
                return;
            }
            if (width < 1 || height < 1 || width > maxGrid || height > maxGrid) {
                respond(exchange, 400, "text/html; charset=utf-8", htmlPage("Ungültige Größe",
                        "<p>Breite/Höhe müssen zwischen 1 und " + maxGrid + " liegen.</p>"));
                return;
            }

            boolean autoPlace = "on".equalsIgnoreCase(autoplace) || "true".equalsIgnoreCase(autoplace);
            String jobCode = plugin.getJobStore().generateUniqueJobCode();
            String imageName = (name == null || name.isBlank()) ? "Bild" : name;

            Path jobFolder = plugin.getJobStore().getJobFolder(jobCode);
            try {
                ImageProcessor.processAndSaveTiles(imageBytes, width, height, jobFolder);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Bildverarbeitung fehlgeschlagen", e);
                respond(exchange, 400, "text/html; charset=utf-8", htmlPage("Fehler bei Bildverarbeitung",
                        "<p>Das Bild konnte nicht verarbeitet werden: " + escape(e.getMessage()) + "</p>"));
                return;
            }

            long now = System.currentTimeMillis();
            long expiryMs = plugin.getConfig().getInt("job-expiry-minutes", 120) * 60_000L;
            MapJob job = new MapJob(jobCode, pending.ownerId, pending.ownerName, imageName,
                    width, height, autoPlace, now, now + expiryMs);
            plugin.getJobStore().addJob(job);
            plugin.getJobStore().removePendingUpload(token);

            // Spieler im Spiel benachrichtigen (muss auf dem Main-Thread laufen)
            notifyPlayerIngame(pending.ownerId, jobCode);

            respond(exchange, 200, "text/html; charset=utf-8", successHtml(jobCode, width, height));
        }
    }

    private void notifyPlayerIngame(UUID playerId, String jobCode) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                plugin.sendConfirmMessage(player, jobCode);
            }
        });
    }

    private byte[] readAllLimited(java.io.InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String htmlPage(String title, String bodyHtml) {
        return "<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>" + escape(title) + "</title>" + baseStyle() + "</head><body>" +
                "<div class=\"card\"><h1>" + escape(title) + "</h1>" + bodyHtml + "</div></body></html>";
    }

    private String baseStyle() {
        return "<style>" +
                "body{font-family:system-ui,sans-serif;background:#1e1f22;color:#eee;display:flex;" +
                "justify-content:center;padding:40px 16px;margin:0;}" +
                ".card{background:#2b2d31;padding:28px 32px;border-radius:12px;max-width:480px;width:100%;" +
                "box-shadow:0 4px 24px rgba(0,0,0,.4);}" +
                "h1{font-size:20px;margin-top:0;}" +
                "label{display:block;margin:14px 0 6px;font-size:14px;color:#bbb;}" +
                "input[type=text],input[type=number]{width:100%;padding:8px 10px;border-radius:6px;" +
                "border:1px solid #444;background:#1e1f22;color:#eee;box-sizing:border-box;}" +
                "input[type=file]{width:100%;color:#eee;}" +
                ".row{display:flex;gap:12px;}" +
                ".row>div{flex:1;}" +
                "button{margin-top:20px;width:100%;padding:10px;border:none;border-radius:6px;" +
                "background:#5865F2;color:#fff;font-size:15px;cursor:pointer;}" +
                "button:hover{background:#4752c4;}" +
                "code{background:#1e1f22;padding:2px 6px;border-radius:4px;}" +
                ".code-box{font-size:28px;letter-spacing:4px;text-align:center;background:#1e1f22;" +
                "padding:14px;border-radius:8px;margin:16px 0;color:#5865F2;font-weight:bold;}" +
                ".checkbox-row{display:flex;align-items:center;gap:8px;margin-top:14px;}" +
                "small{color:#888;}" +
                "</style>";
    }

    private String uploadFormHtml(String token) {
        String body =
                "<form method=\"POST\" action=\"/upload\" enctype=\"multipart/form-data\">" +
                "<input type=\"hidden\" name=\"token\" value=\"" + escape(token) + "\">" +
                "<label>Bilddatei</label>" +
                "<input type=\"file\" name=\"file\" accept=\"image/*\" required>" +
                "<label>Name (optional)</label>" +
                "<input type=\"text\" name=\"name\" placeholder=\"z.B. Mein Wandbild\">" +
                "<div class=\"row\">" +
                "<div><label>Breite (Karten)</label><input type=\"number\" name=\"width\" value=\"11\" min=\"1\" max=\"25\" required></div>" +
                "<div><label>Höhe (Karten)</label><input type=\"number\" name=\"height\" value=\"11\" min=\"1\" max=\"25\" required></div>" +
                "</div>" +
                "<div class=\"checkbox-row\">" +
                "<input type=\"checkbox\" id=\"autoplace\" name=\"autoplace\">" +
                "<label for=\"autoplace\" style=\"margin:0;\">Auto-Place aktivieren (versucht Item Frames automatisch an der Wand zu platzieren, die du im Spiel ansiehst)</label>" +
                "</div>" +
                "<button type=\"submit\">Hochladen &amp; verarbeiten</button>" +
                "</form>" +
                "<p><small>1 Karte = 128×128 Pixel im Spiel. Bei 11×11 wird dein Bild auf 1408×1408px skaliert.</small></p>";
        return htmlPage("Bild-Map Upload", body);
    }

    private String successHtml(String jobCode, int width, int height) {
        String body =
                "<p>Dein Bild wurde verarbeitet (" + width + "×" + height + " Karten = " + (width * height) + " Karten insgesamt).</p>" +
                "<p>Du solltest bereits eine Nachricht im Spiel bekommen haben. Falls nicht, gib diesen Befehl im Chat ein:</p>" +
                "<div class=\"code-box\">/imagemap confirm " + escape(jobCode) + "</div>" +
                "<p><small>Der Code ist begrenzt gültig. Beim Abholen bekommst du die Karten nummeriert (Zeile/Spalte) ins Inventar.</small></p>";
        return htmlPage("Fertig!", body);
    }
}
