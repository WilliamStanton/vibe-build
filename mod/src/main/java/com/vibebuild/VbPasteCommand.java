package com.vibebuild;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.vibebuild.session.BuildSession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * /vb paste <x> <y> <z> <rotation>
 *
 * Called server-side by the PlacementController after the player left-clicks
 * to confirm ghost placement. Pastes the WE clipboard at the given position.
 */
public class VbPasteCommand {

    /**
     * Registers the {@code /vb paste <x> <y> <z> <rotation>} subcommand.
     *
     * Only available to players whose session is in the {@code PREVIEWING} phase.
     * Applies the optional rotation (0, 90, 180, or 270 degrees) to the WorldEdit
     * clipboard, then pastes it at the given coordinates in the player's current world.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("vb")
                .then(
                    Commands.literal("paste")
                        .requires(src -> {
                            if (!src.isPlayer()) return false;
                            ServerPlayer p = src.getPlayer();
                            if (p == null) return false;
                            BuildSession s = Vibebuild.getInstance().getSessions()
                                    .get(p.getName().getString());
                            return s != null && s.phase == BuildSession.Phase.PREVIEWING;
                        })
                        .then(
                            Commands.argument("x", IntegerArgumentType.integer())
                                .then(
                                    Commands.argument("y", IntegerArgumentType.integer())
                                        .then(
                                            Commands.argument("z", IntegerArgumentType.integer())
                                                .then(
                                                    Commands.argument("rotation", IntegerArgumentType.integer(0, 270))
                                                        .executes(ctx -> {
                                                            ServerPlayer player = ctx.getSource().getPlayer();
                                                            if (player == null) return 0;

                                                            int x   = IntegerArgumentType.getInteger(ctx, "x");
                                                            int y   = IntegerArgumentType.getInteger(ctx, "y");
                                                            int z   = IntegerArgumentType.getInteger(ctx, "z");
                                                            int rot = IntegerArgumentType.getInteger(ctx, "rotation");

                                                            try {
                                                                Actor  actor   = FabricAdapter.adaptPlayer(player);
                                                                World  weWorld = FabricAdapter.adapt(player.level());

                                                                ClipboardHolder holder = WorldEdit.getInstance()
                                                                        .getSessionManager()
                                                                        .get(actor)
                                                                        .getClipboard();

                                                                if (rot != 0) {
                                                                    holder.setTransform(new AffineTransform().rotateY(rot));
                                                                }

                                                                try (EditSession es = WorldEdit.getInstance()
                                                                        .newEditSessionBuilder()
                                                                        .world(weWorld)
                                                                        .build()) {

                                                                    Operation paste = holder
                                                                            .createPaste(es)
                                                                            .to(BlockVector3.at(x, y, z))
                                                                            .ignoreAirBlocks(false)
                                                                            .build();

                                                                    Operations.complete(paste);
                                                                }

                                                                String name = player.getName().getString();
                                                                BuildSession session = Vibebuild.getInstance().getSessions().get(name);
                                                                if (session != null) session.phase = BuildSession.Phase.CONNECTED;

                                                                player.sendSystemMessage(ChatUtil.vb("Build placed! Enjoy."));
                                                                return 1;

                                                            } catch (Exception e) {
                                                                Vibebuild.LOGGER.error("[VB] Paste failed", e);
                                                                player.sendSystemMessage(ChatUtil.vbError("Paste failed: " + e.getMessage()));
                                                                return 0;
                                                            }
                                                        })
                                                )
                                        )
                                )
                        )
                )
        );
    }
}
