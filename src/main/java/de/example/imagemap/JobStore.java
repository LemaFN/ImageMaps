package de.example.imagemap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verwaltet alle offenen Uploads, fertige Jobs und die Map-ID -> Kachel Zuordnung.
 * Alles wird bei jeder Änderung sofort auf die Platte geschrieben (jobs.json / maps.json),
 * damit ein Server-Crash oder -Neustart keine Daten verliert.
 */
public class JobStore {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path dataFolder;
    private final Path jobsFile;
    private final Path mapsFile;
    private final Path anchorsFile;
    private final Logger logger;

    private final Map<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();
    private final Map<String, MapJob> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, MapTileRef> mapRegistry = new ConcurrentHashMap<>();
    // Anker-Map-ID -> Job-Code. Wird angelegt, wenn eine Auto-Place-Karte ausgegeben wird;
    // sobald der Spieler diese Karte in einen leeren Item Frame setzt, löst das den
    // automatischen Rest-Aufbau aus (siehe AutoPlaceListener).
    private final Map<Integer, String> autoPlaceAnchors = new ConcurrentHashMap<>();

    public JobStore(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.jobsFile = dataFolder.resolve("jobs.json");
        this.mapsFile = dataFolder.resolve("maps.json");
        this.anchorsFile = dataFolder.resolve("anchors.json");
        this.logger = logger;
    }

    public Path getDataFolder() {
        return dataFolder;
    }

    public Path getJobFolder(String jobCode) {
        return dataFolder.resolve("jobs").resolve(jobCode);
    }

    // ---------- Pending uploads (nur im Speicher, kurzlebig) ----------

    public void addPendingUpload(PendingUpload upload) {
        pendingUploads.put(upload.token, upload);
    }

    public PendingUpload getPendingUpload(String token) {
        PendingUpload upload = pendingUploads.get(token);
        if (upload != null && upload.isExpired()) {
            pendingUploads.remove(token);
            return null;
        }
        return upload;
    }

    public void removePendingUpload(String token) {
        pendingUploads.remove(token);
    }

    // ---------- Jobs (persistent) ----------

    public void addJob(MapJob job) {
        jobs.put(job.code, job);
        saveJobs();
    }

    public MapJob getJob(String code) {
        MapJob job = jobs.get(code);
        if (job != null && job.isExpired() && !job.consumed) {
            return job; // trotzdem zurückgeben, Command entscheidet über Ablauf-Meldung
        }
        return job;
    }

    public void markConsumed(String code) {
        MapJob job = jobs.get(code);
        if (job != null) {
            job.consumed = true;
            saveJobs();
        }
    }

    // ---------- Map registry (persistent, der eigentliche Bugfix) ----------

    public void registerMap(MapTileRef ref) {
        mapRegistry.put(ref.mapId, ref);
        saveMaps();
    }

    public List<MapTileRef> getAllMapRefs() {
        return new ArrayList<>(mapRegistry.values());
    }

    /** Alle Kachel-Referenzen eines Jobs, sortiert nach Zeile dann Spalte (Lese-Reihenfolge). */
    public List<MapTileRef> getMapRefsForJob(String jobCode) {
        List<MapTileRef> result = new ArrayList<>();
        for (MapTileRef ref : mapRegistry.values()) {
            if (ref.jobCode.equals(jobCode)) {
                result.add(ref);
            }
        }
        result.sort((a, b) -> a.row != b.row ? Integer.compare(a.row, b.row) : Integer.compare(a.col, b.col));
        return result;
    }

    // ---------- Auto-Place-Anker (persistent) ----------

    public void registerAutoPlaceAnchor(int mapId, String jobCode) {
        autoPlaceAnchors.put(mapId, jobCode);
        saveAnchors();
    }

    /** Liefert den Job-Code, falls die angegebene Map-ID ein wartender Auto-Place-Anker ist, sonst null. */
    public String getAutoPlaceJobCode(int mapId) {
        return autoPlaceAnchors.get(mapId);
    }

    public void removeAutoPlaceAnchor(int mapId) {
        autoPlaceAnchors.remove(mapId);
        saveAnchors();
    }

    // ---------- Laden/Speichern ----------

    public void load() {
        try {
            if (Files.exists(jobsFile)) {
                String json = Files.readString(jobsFile, StandardCharsets.UTF_8);
                Type type = new TypeToken<Map<String, MapJob>>() {}.getType();
                Map<String, MapJob> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    jobs.putAll(loaded);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Konnte jobs.json nicht laden", e);
        }

        try {
            if (Files.exists(mapsFile)) {
                String json = Files.readString(mapsFile, StandardCharsets.UTF_8);
                Type type = new TypeToken<Map<Integer, MapTileRef>>() {}.getType();
                Map<Integer, MapTileRef> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    mapRegistry.putAll(loaded);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Konnte maps.json nicht laden", e);
        }

        try {
            if (Files.exists(anchorsFile)) {
                String json = Files.readString(anchorsFile, StandardCharsets.UTF_8);
                Type type = new TypeToken<Map<Integer, String>>() {}.getType();
                Map<Integer, String> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    autoPlaceAnchors.putAll(loaded);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Konnte anchors.json nicht laden", e);
        }
    }

    public synchronized void saveJobs() {
        try {
            Files.createDirectories(dataFolder);
            Files.writeString(jobsFile, gson.toJson(jobs), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Konnte jobs.json nicht speichern", e);
        }
    }

    public synchronized void saveMaps() {
        try {
            Files.createDirectories(dataFolder);
            Files.writeString(mapsFile, gson.toJson(mapRegistry), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Konnte maps.json nicht speichern", e);
        }
    }

    public synchronized void saveAnchors() {
        try {
            Files.createDirectories(dataFolder);
            Files.writeString(anchorsFile, gson.toJson(autoPlaceAnchors), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Konnte anchors.json nicht speichern", e);
        }
    }

    public String generateUniqueJobCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.Random rnd = new java.util.Random();
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(chars.charAt(rnd.nextInt(chars.length())));
            }
            code = sb.toString();
        } while (jobs.containsKey(code));
        return code;
    }
}
