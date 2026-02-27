package com.vibebuild.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.ai.BuildPipeline;
import com.vibebuild.config.VibeBuildConfig;
import com.vibebuild.network.CancelPreviewPayload;
import com.vibebuild.session.BuildSession;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers the /vb command tree.
 *
 * /vb apikey <key>         — set Anthropic API key (persisted)
 * /vb cancel               — cancel current build and teleport back
 * /vb confirm              — accept reviewed build and return to place it
 * /vb <prompt...>          — send a build prompt to the AI
 */
public class VbCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("vb")
                .requires(CommandSourceStack::isPlayer)

                // /vb apikey <key>
                .then(Commands.literal("apikey")
                    .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(VbCommand::apikey)))

                // /vb model [name]
                .then(Commands.literal("model")
                    .executes(VbCommand::modelGet)
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(VbCommand::modelSet)))

                // /vb cancel
                .then(Commands.literal("cancel")
                    .executes(VbCommand::cancel))

                // /vb confirm
                .then(Commands.literal("confirm")
                    .executes(VbCommand::confirm))

                // /vb <prompt...>
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                    .executes(VbCommand::prompt))
        );
    }

    // ── Handlers ──

    private static int apikey(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String key = StringArgumentType.getString(ctx, "key");
        VibeBuildConfig.setApiKey(key);

        // Mask the key in chat for safety
        String masked = key.length() > 8
                ? key.substring(0, 4) + "..." + key.substring(key.length() - 4)
                : "****";
        player.sendSystemMessage(ChatUtil.vb("API key set: " + masked));
        return 1;
    }

    private static int modelGet(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        player.sendSystemMessage(ChatUtil.vb("Current model: " + VibeBuildConfig.getModel()));
        return 1;
    }

    private static int modelSet(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = StringArgumentType.getString(ctx, "name");
        VibeBuildConfig.setModel(name);
        player.sendSystemMessage(ChatUtil.vb("Model set to: " + name));
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();
        BuildSession session = Vibebuild.getInstance().getSessions().get(name);

        if (session == null) {
            player.sendSystemMessage(ChatUtil.vb("No active session."));
            return 0;
        }

        if (session.phase == BuildSession.Phase.CONNECTED || session.phase == BuildSession.Phase.IDLE) {
            player.sendSystemMessage(ChatUtil.vb("Nothing to cancel."));
            return 0;
        }

        // If building/planning, signal the pipeline to stop
        if (session.phase == BuildSession.Phase.BUILDING || session.phase == BuildSession.Phase.PLANNING) {
            session.cancelled = true;
        }

        // If previewing, tell the client to deactivate the ghost
        if (session.phase == BuildSession.Phase.PREVIEWING) {
            ServerPlayNetworking.send(player, new CancelPreviewPayload());
        }

        // Teleport back from build dimension (no-op if already back)
        Vibebuild.getInstance().getBuildDimension().teleportBack(player, session);

        session.phase = BuildSession.Phase.CONNECTED;

        player.sendSystemMessage(ChatUtil.vb("Build cancelled. Returned to your world."));
        Vibebuild.LOGGER.info("[VB] {} cancelled their build", name);
        return 1;
    }

    private static int confirm(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();
        BuildSession session = Vibebuild.getInstance().getSessions().get(name);

        if (session == null) {
            player.sendSystemMessage(ChatUtil.vb("No active session."));
            return 0;
        }

        if (session.phase != BuildSession.Phase.REVIEWING) {
            player.sendSystemMessage(ChatUtil.vb("Nothing to confirm. Build something first with /vb <prompt>."));
            return 0;
        }

        // Teleport back to original world
        Vibebuild.getInstance().getBuildDimension().teleportBack(player, session);

        session.phase = BuildSession.Phase.PREVIEWING;

        player.sendSystemMessage(ChatUtil.vb("Build confirmed! Use the ghost preview to place it."));
        player.sendSystemMessage(ChatUtil.vb("Left-click to place, R to rotate, PgUp/PgDn to adjust height."));

        Vibebuild.getInstance().activateClientPreview(player);

        Vibebuild.LOGGER.info("[VB] {} confirmed their build", name);
        return 1;
    }

    private static int prompt(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name   = player.getName().getString();
        String prompt = StringArgumentType.getString(ctx, "prompt");

        BuildSession session = Vibebuild.getInstance().getSessions().get(name);

        if (session == null) {
            player.sendSystemMessage(ChatUtil.vbError("No active session. Rejoin the server."));
            return 0;
        }

        if (session.phase != BuildSession.Phase.CONNECTED && session.phase != BuildSession.Phase.REVIEWING) {
            player.sendSystemMessage(ChatUtil.vb("Busy — wait for the current build to finish, or /vb cancel."));
            return 0;
        }

        // Determine player position (use saved pos if already in build dimension)
        double x, y, z;
        if (session.inVibeWorldSession) {
            x = session.originalX;
            y = session.originalY;
            z = session.originalZ;
        } else {
            x = player.getX();
            y = player.getY();
            z = player.getZ();
        }

        BlockPos playerPos = new BlockPos(
                (int) Math.round(x),
                (int) Math.round(y),
                (int) Math.round(z)
        );

        player.sendSystemMessage(ChatUtil.vb("Sending: " + prompt));

        // Launch the AI pipeline on a background thread
        BuildPipeline.runAsync(player, session, prompt, playerPos);
        return 1;
    }
}
