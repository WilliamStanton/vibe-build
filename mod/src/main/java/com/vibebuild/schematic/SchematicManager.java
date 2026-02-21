package com.vibebuild.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.vibebuild.Vibebuild;
import com.vibebuild.session.BuildSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * After a build is complete, copies the built region from the void dimension
 * into the player's WorldEdit clipboard so they can paste it anywhere.
 */
public class SchematicManager {

    /**
     * Copies the built region to the player's WorldEdit clipboard.
     *
     * @return true if the copy succeeded
     */
    public boolean saveBuildToClipboard(ServerPlayer player, BuildSession session) {
        if (session.buildMin == null || session.buildMax == null) {
            Vibebuild.LOGGER.warn("[VB] No build bounds recorded for {}", session.playerName);
            return false;
        }

        try {
            World weWorld = FabricAdapter.adapt(player.level());
            Actor actor   = FabricAdapter.adaptPlayer(player);

            BlockVector3 min = bv3(session.buildMin);
            BlockVector3 max = bv3(session.buildMax);
            CuboidRegion region = new CuboidRegion(weWorld, min, max);

            // Copy region into a clipboard
            com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard clipboard =
                    new com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard(region);
            clipboard.setOrigin(min);

            try (EditSession es = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(weWorld)
                    .build()) {

                com.sk89q.worldedit.function.operation.ForwardExtentCopy copy =
                        new com.sk89q.worldedit.function.operation.ForwardExtentCopy(
                                es, region, clipboard, min);
                copy.setCopyingEntities(false);
                com.sk89q.worldedit.function.operation.Operations.complete(copy);
            }

            // Store in the player's LocalSession
            WorldEdit.getInstance()
                    .getSessionManager()
                    .get(actor)
                    .setClipboard(new ClipboardHolder(clipboard));

            Vibebuild.LOGGER.info("[VB] Clipboard set for {} ({} blocks)",
                    session.playerName, region.getVolume());
            return true;

        } catch (Exception e) {
            Vibebuild.LOGGER.error("[VB] Failed to copy build to clipboard: {}", e.getMessage(), e);
            return false;
        }
    }

    private BlockVector3 bv3(BlockPos p) {
        return BlockVector3.at(p.getX(), p.getY(), p.getZ());
    }
}
