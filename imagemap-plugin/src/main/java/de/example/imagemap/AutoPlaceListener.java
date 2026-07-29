package de.example.imagemap;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Kern der "eine Karte platzieren, Rest ergänzt sich automatisch"-Funktion:
 * Sobald ein Spieler eine Karte in einen (bereits vorhandenen, leeren) Item Frame setzt,
 * prüfen wir ob diese Map-ID als Auto-Place-Anker registriert ist. Falls ja, spawnen wir
 * die restlichen Item Frames für den Job automatisch nach rechts/unten, ausgehend von der
 * Position und Blickrichtung des gerade befüllten Frames.
 */
public class AutoPlaceListener implements Listener {

    private final ImageMapPlugin plugin;

    public AutoPlaceListener(ImageMapPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        if (event.getAction() != PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE) {
            return;
        }

        ItemStack placedItem = event.getItemStack();
        if (placedItem == null || !(placedItem.getItemMeta() instanceof MapMeta mapMeta)) {
            return;
        }
        if (mapMeta.getMapView() == null) {
            return;
        }

        int mapId = mapMeta.getMapView().getId();
        String jobCode = plugin.getJobStore().getAutoPlaceJobCode(mapId);
        if (jobCode == null) {
            return; // ganz normale Karte, kein Auto-Place-Anker
        }

        // Anker wird nur einmal ausgelöst.
        plugin.getJobStore().removeAutoPlaceAnchor(mapId);

        MapJob job = plugin.getJobStore().getJob(jobCode);
        if (job == null) {
            plugin.getLogger().warning("Auto-Place-Anker gefunden, aber Job " + jobCode + " existiert nicht mehr.");
            return;
        }

        ItemFrame anchorFrame = event.getItemFrame();
        BlockFace face = anchorFrame.getFacing();
        Vector right = rightVectorFor(face);
        Player player = event.getPlayer();

        if (right == null) {
            player.sendMessage(Component.text("[ImageMap] Auto-Place funktioniert nur an geraden Wänden (Nord/Süd/Ost/West), nicht an Decke/Boden. Die restlichen Karten bekommst du stattdessen ins Inventar.", NamedTextColor.YELLOW));
            giveRemainingTilesToInventory(player, jobCode, mapId);
            return;
        }

        List<MapTileRef> refs = plugin.getJobStore().getMapRefsForJob(jobCode);
        Location anchorLoc = anchorFrame.getLocation();
        World world = anchorFrame.getWorld();
        List<ItemFrame> spawned = new ArrayList<>();

        try {
            for (MapTileRef ref : refs) {
                if (ref.mapId == mapId) {
                    continue; // das ist der Anker, der steht schon
                }
                MapView view = Bukkit.getMap(ref.mapId);
                if (view == null) {
                    continue;
                }
                ItemStack item = ImageMapCommand.buildItemStackForTile(view, job, ref.col, ref.row);

                Location frameLoc = anchorLoc.clone()
                        .add(right.clone().multiply(ref.col))
                        .add(0, -ref.row, 0);

                ItemFrame frame = world.spawn(frameLoc, ItemFrame.class, f -> f.setFacingDirection(face, true));
                frame.setItem(item, false);
                spawned.add(frame);
            }
            player.sendMessage(Component.text("[ImageMap] Die restlichen " + spawned.size() + " Karten wurden automatisch ergänzt!", NamedTextColor.GREEN));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Auto-Place fehlgeschlagen für Job " + jobCode + ", entferne bereits gespawnte Frames", e);
            spawned.forEach(ItemFrame::remove);
            player.sendMessage(Component.text("[ImageMap] Beim automatischen Platzieren ist ein Fehler aufgetreten. Bitte Konsole prüfen.", NamedTextColor.RED));
        }
    }

    private void giveRemainingTilesToInventory(Player player, String jobCode, int anchorMapId) {
        MapJob job = plugin.getJobStore().getJob(jobCode);
        if (job == null) return;
        for (MapTileRef ref : plugin.getJobStore().getMapRefsForJob(jobCode)) {
            if (ref.mapId == anchorMapId) continue;
            MapView view = Bukkit.getMap(ref.mapId);
            if (view == null) continue;
            ItemStack item = ImageMapCommand.buildItemStackForTile(view, job, ref.col, ref.row);
            var leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    /**
     * Rechts-Vektor relativ zur Wand, abhängig davon wohin der Item Frame zeigt.
     * Gleiche Konvention wie zuvor beim Raycast-Ansatz: nur Nord/Süd/Ost/West-Wände,
     * kein Boden/Decke (zu viele Sonderfälle für Spalten-/Zeilenrichtung).
     */
    private Vector rightVectorFor(BlockFace face) {
        return switch (face) {
            case NORTH -> new Vector(1, 0, 0);
            case SOUTH -> new Vector(-1, 0, 0);
            case EAST -> new Vector(0, 0, 1);
            case WEST -> new Vector(0, 0, -1);
            default -> null;
        };
    }
}
