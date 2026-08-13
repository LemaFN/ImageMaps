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

        // Anker wird nur einmal ausgelöst, unabhängig davon ob die folgende Validierung durchkommt -
        // eine einmal fehlerhafte/veraltete Zuordnung soll sich nicht ständig wiederholen können.
        plugin.getJobStore().removeAutoPlaceAnchor(mapId);

        MapJob job = plugin.getJobStore().getJob(jobCode);
        if (job == null || job.isExpired()) {
            plugin.getLogger().warning("Auto-Place-Anker (Map-ID " + mapId + ") verweist auf abgelaufenen/nicht mehr "
                    + "existierenden Job " + jobCode + " - wird ignoriert. Vermutlich eine Altlast aus einem "
                    + "früheren, fehlgeschlagenen Versuch; Map-IDs können vom Server über die Zeit wiederverwendet werden.");
            return;
        }

        // Zusätzliche Absicherung: die Map-ID muss wirklich zur Kachel (Spalte 0, Zeile 0) DIESES
        // Jobs gehören. Falls Map-IDs vom Server jemals wiederverwendet werden und eine veraltete
        // Anker-Zuordnung übrig bleibt, verhindert das einen fehlerhaften Auslöser mit falschem Bild.
        MapTileRef anchorRef = plugin.getJobStore().getMapRef(mapId);
        if (anchorRef == null || !anchorRef.jobCode.equals(jobCode) || anchorRef.col != 0 || anchorRef.row != 0) {
            plugin.getLogger().warning("Auto-Place-Anker (Map-ID " + mapId + ") passt nicht zur erwarteten "
                    + "oben-links-Kachel von Job " + jobCode + " - wird ignoriert (vermutlich eine Altlast).");
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
        Location anchorLoc = snapToGrid(anchorFrame.getLocation(), face);
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
     *
     * Wichtig: frame.getFacing() zeigt AUS der Wand heraus (Richtung Betrachter), der Spieler
     * blickt also in die GENAU ENTGEGENGESETZTE Richtung, wenn er die Karte von vorne ansieht.
     * "Rechts" aus Spielersicht ist die um 90° im Uhrzeigersinn gedrehte Blickrichtung
     * (Kompass-Konvention: Nord->Ost->Süd->West->Nord).
     *
     * Beispiel: facing == NORTH (Rahmen zeigt nach Norden/-Z) -> Spieler blickt nach Süden (+Z)
     * -> "rechts" davon ist Westen (-X).
     */
    private Vector rightVectorFor(BlockFace face) {
        return switch (face) {
            case NORTH -> new Vector(-1, 0, 0); // Spieler blickt Süden -> rechts = Westen
            case SOUTH -> new Vector(1, 0, 0);  // Spieler blickt Norden -> rechts = Osten
            case EAST -> new Vector(0, 0, -1);  // Spieler blickt Westen -> rechts = Norden
            case WEST -> new Vector(0, 0, 1);   // Spieler blickt Osten  -> rechts = Süden
            default -> null;
        };
    }

    /**
     * Rundet die beiden Rasterachsen (rechts/hoch, senkrecht zur Blickrichtung) auf ganze
     * Blockkoordinaten. Bukkit liefert bei Hanging-Entities wie Item Frames nicht immer exakt
     * ganzzahlige Koordinaten - kleine Rundungsfehler auf der Rasterachse würden sich beim
     * Multiplizieren mit der Spalten-/Zeilenzahl aufsummieren und zu Versatz/Überlappung
     * benachbarter Karten führen. Die Tiefen-Achse (Blickrichtung) bleibt unverändert, da sie
     * für unsere Gitter-Berechnung ohnehin nicht verwendet wird.
     */
    private Location snapToGrid(Location loc, BlockFace face) {
        double x = loc.getX();
        double y = Math.round(loc.getY());
        double z = loc.getZ();
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
            x = Math.round(x); // rechts-Achse bei Nord/Süd-Wänden
        } else if (face == BlockFace.EAST || face == BlockFace.WEST) {
            z = Math.round(z); // rechts-Achse bei Ost/West-Wänden
        }
        return new Location(loc.getWorld(), x, y, z);
    }
}
