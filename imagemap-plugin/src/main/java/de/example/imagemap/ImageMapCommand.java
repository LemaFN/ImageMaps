package de.example.imagemap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class ImageMapCommand implements CommandExecutor, TabCompleter {

    private final ImageMapPlugin plugin;

    public ImageMapCommand(ImageMapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl nutzen.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Nutzung: /imagemap upload | /imagemap confirm <code>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "upload" -> handleUpload(player);
            case "confirm" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Nutzung: /imagemap confirm <code>", NamedTextColor.YELLOW));
                } else {
                    handleConfirm(player, args[1].toUpperCase());
                }
            }
            default -> player.sendMessage(Component.text("Unbekannter Unterbefehl. Nutze upload oder confirm.", NamedTextColor.RED));
        }
        return true;
    }

    private void handleUpload(Player player) {
        String token = UUID.randomUUID().toString().replace("-", "");
        int expiryMinutes = plugin.getConfig().getInt("upload-link-expiry-minutes", 15);
        long expiresAt = System.currentTimeMillis() + expiryMinutes * 60_000L;

        PendingUpload pending = new PendingUpload(token, player.getUniqueId(), player.getName(), expiresAt);
        plugin.getJobStore().addPendingUpload(pending);

        String url = plugin.buildUploadUrl(token);

        Component msg = Component.text("[ImageMap] ", NamedTextColor.AQUA)
                .append(Component.text("Klicke hier zum Hochladen deines Bildes ", NamedTextColor.GRAY))
                .append(Component.text("(gültig " + expiryMinutes + " Min.)", NamedTextColor.DARK_GRAY))
                .append(Component.text(" » " + url, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(url)));
        player.sendMessage(msg);
    }

    private void handleConfirm(Player player, String code) {
        MapJob job = plugin.getJobStore().getJob(code);
        if (job == null) {
            player.sendMessage(Component.text("[ImageMap] Unbekannter Code.", NamedTextColor.RED));
            return;
        }
        if (job.consumed) {
            player.sendMessage(Component.text("[ImageMap] Dieser Code wurde bereits abgeholt.", NamedTextColor.RED));
            return;
        }
        if (job.isExpired()) {
            player.sendMessage(Component.text("[ImageMap] Dieser Code ist abgelaufen. Lade das Bild erneut hoch.", NamedTextColor.RED));
            return;
        }
        if (!job.ownerId.equals(player.getUniqueId()) && !player.hasPermission("imagemap.admin")) {
            player.sendMessage(Component.text("[ImageMap] Dieser Code gehört einem anderen Spieler.", NamedTextColor.RED));
            return;
        }

        List<ItemStack> mapItems = buildMapItems(player, job);
        if (mapItems == null) {
            player.sendMessage(Component.text("[ImageMap] Fehler beim Erzeugen der Karten. Bitte Konsole prüfen.", NamedTextColor.RED));
            return;
        }

        plugin.getJobStore().markConsumed(code);

        if (job.autoPlace) {
            // Nur die erste Karte (oben-links) aushändigen. Sobald der Spieler sie in einen
            // leeren Item Frame setzt, erkennt der AutoPlaceListener die Map-ID als Anker
            // und ergänzt automatisch den Rest nach rechts/unten (siehe AutoPlaceListener).
            ItemStack anchorItem = mapItems.get(0);
            MapMeta anchorMeta = (MapMeta) anchorItem.getItemMeta();
            int anchorMapId = anchorMeta.getMapView().getId();
            plugin.getJobStore().registerAutoPlaceAnchor(anchorMapId, job.code);

            giveItemsToPlayer(player, List.of(anchorItem));
            player.sendMessage(Component.text("[ImageMap] Auto-Place aktiv! ", NamedTextColor.GREEN)
                    .append(Component.text("Platziere diese eine Karte in einen leeren Item Frame an der Wand - die restlichen " + (mapItems.size() - 1) + " Karten werden automatisch ergänzt.", NamedTextColor.GRAY)));
            return;
        }

        giveItemsToPlayer(player, mapItems);
        player.sendMessage(Component.text("[ImageMap] " + mapItems.size() + " Karten erhalten! Platziere sie von oben-links nach unten-rechts (siehe Beschreibung auf jeder Karte).", NamedTextColor.GREEN));
    }

    /**
     * Erstellt für jede Kachel eines Jobs eine echte, persistente Bukkit-Map mit angehängtem
     * TileMapRenderer und registriert sie in der JobStore-Registry (maps.json), damit sie
     * einen Server-Neustart übersteht. Rückgabe in Lese-Reihenfolge (Zeile für Zeile, links nach rechts) -
     * Index 0 ist also immer die Karte oben-links.
     */
    private List<ItemStack> buildMapItems(Player player, MapJob job) {
        World world = player.getWorld();
        Path jobFolder = plugin.getJobStore().getJobFolder(job.code);
        List<ItemStack> items = new ArrayList<>();

        try {
            for (int row = 0; row < job.height; row++) {
                for (int col = 0; col < job.width; col++) {
                    Path tileFile = jobFolder.resolve("tile_" + col + "_" + row + ".dat");
                    MapView view = Bukkit.createMap(world);
                    new ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
                    view.addRenderer(new TileMapRenderer(tileFile, plugin.getLogger()));
                    view.setLocked(true);

                    plugin.getJobStore().registerMap(new MapTileRef(
                            view.getId(), job.code, col, row, tileFile.toAbsolutePath().toString(), world.getName()));

                    items.add(buildItemStackForTile(view, job, col, row));
                }
            }
            return items;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Fehler beim Erzeugen der Karten für Job " + job.code, e);
            return null;
        }
    }

    /**
     * Baut das anzeigefertige Karten-Item für eine bereits existierende Bukkit-Map.
     * Wird sowohl beim initialen Erzeugen als auch vom AutoPlaceListener beim
     * nachträglichen Ergänzen der restlichen Karten genutzt.
     */
    static ItemStack buildItemStackForTile(MapView view, MapJob job, int col, int row) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.displayName(Component.text(job.imageName + " [" + (col + 1) + "/" + job.width + ", " + (row + 1) + "/" + job.height + "]", NamedTextColor.AQUA));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Spalte " + (col + 1) + " von " + job.width, NamedTextColor.GRAY));
        lore.add(Component.text("Zeile " + (row + 1) + " von " + job.height, NamedTextColor.GRAY));
        lore.add(Component.text("Job: " + job.code, NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void giveItemsToPlayer(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            var leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("upload", "confirm");
        }
        return Collections.emptyList();
    }
}
