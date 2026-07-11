package de.example.imagemap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;

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
            boolean placed = tryAutoPlace(player, job, mapItems);
            if (placed) {
                player.sendMessage(Component.text("[ImageMap] Karten wurden automatisch an der Wand platziert!", NamedTextColor.GREEN));
                return;
            }
            player.sendMessage(Component.text("[ImageMap] Auto-Place war nicht möglich (schau auf eine gerade, freie Wand). Karten kommen stattdessen ins Inventar.", NamedTextColor.YELLOW));
        }

        giveItemsToPlayer(player, mapItems);
        player.sendMessage(Component.text("[ImageMap] " + mapItems.size() + " Karten erhalten! Platziere sie von oben-links nach unten-rechts (siehe Beschreibung auf jeder Karte).", NamedTextColor.GREEN));
    }

    /**
     * Erstellt für jede Kachel eines Jobs eine echte, persistente Bukkit-Map mit angehängtem
     * TileMapRenderer und registriert sie in der JobStore-Registry (maps.json), damit sie
     * einen Server-Neustart übersteht.
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

                    items.add(item);
                }
            }
            return items;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Fehler beim Erzeugen der Karten für Job " + job.code, e);
            return null;
        }
    }

    private void giveItemsToPlayer(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            var leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    /**
     * Best-Effort Auto-Place: funktioniert nur an geraden vertikalen Wänden (Nord/Süd/Ost/West-Fläche),
     * die der Spieler direkt ansieht. Für Boden/Decke wird abgebrochen (zu viele Sonderfälle).
     */
    private boolean tryAutoPlace(Player player, MapJob job, List<ItemStack> mapItems) {
        RayTraceResult result = player.rayTraceBlocks(8);
        if (result == null || result.getHitBlock() == null || result.getHitBlockFace() == null) {
            return false;
        }
        Block target = result.getHitBlock();
        BlockFace face = result.getHitBlockFace();

        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            return false; // v1: nur Wände, keine Decken/Böden
        }

        Vector right = switch (face) {
            case NORTH -> new Vector(1, 0, 0);
            case SOUTH -> new Vector(-1, 0, 0);
            case EAST -> new Vector(0, 0, 1);
            case WEST -> new Vector(0, 0, -1);
            default -> null;
        };
        if (right == null) return false;

        World world = target.getWorld();
        List<ItemFrame> spawned = new ArrayList<>();
        int index = 0;

        try {
            for (int row = 0; row < job.height; row++) {
                for (int col = 0; col < job.width; col++) {
                    Location frameLoc = target.getLocation().add(0.5, 0.5, 0.5)
                            .add(face.getDirection())
                            .add(right.clone().multiply(col))
                            .add(0, -row, 0);

                    ItemFrame frame = world.spawn(frameLoc, ItemFrame.class, f -> {
                        f.setFacingDirection(face, true);
                    });
                    frame.setItem(mapItems.get(index), false);
                    spawned.add(frame);
                    index++;
                }
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Auto-Place fehlgeschlagen, entferne bereits gespawnte Frames", e);
            spawned.forEach(ItemFrame::remove);
            return false;
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
