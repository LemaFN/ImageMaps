package de.example.imagemap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.logging.Level;

public class ImageMapPlugin extends JavaPlugin {

    private JobStore jobStore;
    private UploadServer uploadServer;
    private String cachedPublicHost;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        jobStore = new JobStore(getDataFolder().toPath(), getLogger());
        jobStore.load();

        // Renderer für bereits existierende Karten wiederherstellen -> überlebt Neustarts
        restoreMapRenderers();

        int port = getConfig().getInt("port", 8853);
        uploadServer = new UploadServer(this);
        try {
            uploadServer.start(port);
            getLogger().info("ImageMap Upload-Server läuft auf Port " + port);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Konnte Upload-Server nicht starten (Port " + port + " belegt?)", e);
        }

        ImageMapCommand executor = new ImageMapCommand(this);
        getCommand("imagemap").setExecutor(executor);
        getCommand("imagemap").setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (uploadServer != null) {
            uploadServer.stop();
        }
        if (jobStore != null) {
            jobStore.saveJobs();
            jobStore.saveMaps();
        }
    }

    private void restoreMapRenderers() {
        int restored = 0;
        for (MapTileRef ref : jobStore.getAllMapRefs()) {
            try {
                MapView view = Bukkit.getMap(ref.mapId);
                if (view == null) {
                    continue; // Map existiert nicht mehr (z.B. manuell gelöscht)
                }
                new java.util.ArrayList<>(view.getRenderers()).forEach(r -> {
                    if (r instanceof TileMapRenderer) {
                        view.removeRenderer(r);
                    }
                });
                Path tileFile = Path.of(ref.tileFile);
                view.addRenderer(new TileMapRenderer(tileFile, getLogger()));
                restored++;
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Konnte Map " + ref.mapId + " nicht wiederherstellen", e);
            }
        }
        if (restored > 0) {
            getLogger().info(restored + " Bild-Karten nach Neustart wiederhergestellt.");
        }
    }

    public JobStore getJobStore() {
        return jobStore;
    }

    public void sendConfirmMessage(Player player, String jobCode) {
        Component msg = Component.text("[ImageMap] ", NamedTextColor.AQUA)
                .append(Component.text("Bild verarbeitet! Klicke hier oder gib den Befehl ein: ", NamedTextColor.GRAY))
                .append(Component.text("/imagemap confirm " + jobCode, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/imagemap confirm " + jobCode)));
        player.sendMessage(msg);
    }

    /**
     * Baut die öffentliche Upload-URL. Nutzt public-host aus der config, sonst Best-Effort
     * automatische Ermittlung der lokalen IPv4-Adresse (funktioniert NICHT zuverlässig hinter NAT!).
     */
    public String buildUploadUrl(String token) {
        String host = getConfig().getString("public-host", "");
        int port = getConfig().getInt("port", 8853);
        if (host == null || host.isBlank()) {
            host = detectPublicHost();
        }
        return "http://" + host + ":" + port + "/upload/" + token;
    }

    private String detectPublicHost() {
        if (cachedPublicHost != null) {
            return cachedPublicHost;
        }
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        cachedPublicHost = addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (cachedPublicHost == null) {
            cachedPublicHost = "DEINE-SERVER-IP";
        }
        return cachedPublicHost;
    }
}
