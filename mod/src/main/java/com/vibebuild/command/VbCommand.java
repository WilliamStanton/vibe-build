package com.vibebuild.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.network.CancelPreviewPayload;
import com.vibebuild.network.VbWebSocketClient;
import com.vibebuild.session.BuildSession;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * Registers the /vb command.
 *
 * /vb connect              — open WebSocket connection
 * /vb disconnect           — close WebSocket connection
 * /vb cancel               — cancel current build and teleport back
 * /vb confirm              — accept reviewed build and return to place it
 * /vb <prompt...>          — send a build prompt to the server
 */
public class VbCommand {

    private static final String WS_URL = "ws://localhost:8080";
    private static final String IMAGE_INPUT_URL = resolveImageInputUrl();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("vb")
                .requires(src -> src.isPlayer())

                // /vb connect
                .then(Commands.literal("connect")
                    .executes(VbCommand::connect))

                // /vb disconnect
                .then(Commands.literal("disconnect")
                    .executes(VbCommand::disconnect))

                // /vb cancel
                .then(Commands.literal("cancel")
                    .executes(VbCommand::cancel))

                // /vb confirm
                .then(Commands.literal("confirm")
                    .executes(VbCommand::confirm))

                // /vb image
                .then(Commands.literal("image")
                    .executes(VbCommand::image))

                // /vb <prompt...>
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                    .executes(VbCommand::prompt))
        );
    }

    // ── Handlers ──

    private static int connect(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();

        if (Vibebuild.getInstance().getSessions().containsKey(name)) {
            player.sendSystemMessage(ChatUtil.vb("Already connected. Use /vb disconnect first."));
            return 0;
        }

        try {
            BuildSession session = new BuildSession(name);
            VbWebSocketClient ws = new VbWebSocketClient(
                new URI(WS_URL),
                () -> Vibebuild.getInstance().getServer().getPlayerList().getPlayerByName(name),
                session
            );
            ws.connect();
            Vibebuild.getInstance().getSessions().put(name, session);
            Vibebuild.getInstance().getWebSockets().put(name, ws);
            player.sendSystemMessage(ChatUtil.vb("Connecting to " + WS_URL + "..."));
        } catch (Exception e) {
            player.sendSystemMessage(ChatUtil.vbError("Failed to connect: " + e.getMessage()));
            Vibebuild.LOGGER.error("[VB] connect error", e);
        }

        return 1;
    }

    private static int disconnect(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();
        VbWebSocketClient ws = Vibebuild.getInstance().getWebSockets().remove(name);
        BuildSession session  = Vibebuild.getInstance().getSessions().remove(name);

        if (ws == null || session == null) {
            player.sendSystemMessage(ChatUtil.vb("Not connected."));
            return 0;
        }

        // Teleport back if stuck in build dimension
        Vibebuild.getInstance().getBuildDimension().teleportBack(player, session);

        ws.close();
        player.sendSystemMessage(ChatUtil.vb("Disconnected."));
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();
        VbWebSocketClient ws      = Vibebuild.getInstance().getWebSockets().get(name);
        BuildSession      session = Vibebuild.getInstance().getSessions().get(name);

        if (ws == null || session == null) {
            player.sendSystemMessage(ChatUtil.vb("Not connected."));
            return 0;
        }

        if (session.phase == BuildSession.Phase.CONNECTED || session.phase == BuildSession.Phase.IDLE) {
            player.sendSystemMessage(ChatUtil.vb("Nothing to cancel."));
            return 0;
        }

        // If building/planning, notify the server to stop generating
        if (session.phase == BuildSession.Phase.BUILDING || session.phase == BuildSession.Phase.PLANNING) {
            ws.sendCancel();
        }

        // If previewing, tell the client to deactivate the ghost
        if (session.phase == BuildSession.Phase.PREVIEWING) {
            ServerPlayNetworking.send(player, new CancelPreviewPayload());
        }

        // Teleport back from build dimension (no-op if already back)
        Vibebuild.getInstance().getBuildDimension().teleportBack(player, session);

        // Reset session phase (keep buildMin/buildMax so resetBuildWorld can clear them next time)
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
            player.sendSystemMessage(ChatUtil.vb("Not connected."));
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

        // Tell the client to activate ghost preview using previously captured blocks
        Vibebuild.getInstance().activateClientPreview(player);

        Vibebuild.LOGGER.info("[VB] {} confirmed their build", name);
        return 1;
    }

    private static int prompt(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name   = player.getName().getString();
        String prompt = StringArgumentType.getString(ctx, "prompt");

        VbWebSocketClient ws      = Vibebuild.getInstance().getWebSockets().get(name);
        BuildSession      session = Vibebuild.getInstance().getSessions().get(name);

        if (ws == null || !ws.isOpen()) {
            player.sendSystemMessage(ChatUtil.vb("Not connected. Run /vb connect first."));
            return 0;
        }

        if (session.phase != BuildSession.Phase.CONNECTED && session.phase != BuildSession.Phase.REVIEWING) {
            player.sendSystemMessage(ChatUtil.vb("Busy -- wait for the current build to finish, or /vb cancel."));
            return 0;
        }

        session.phase = BuildSession.Phase.PLANNING;

        // Capture current position as build origin hint
        // (if in vibe world session, use saved original pos from the overworld)
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

        player.sendSystemMessage(ChatUtil.vb("Sending: " + prompt));
        ws.sendPrompt(prompt, x, y, z);
        return 1;
    }

    private static int image(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();
        VbWebSocketClient ws = Vibebuild.getInstance().getWebSockets().get(name);
        BuildSession session = Vibebuild.getInstance().getSessions().get(name);

        if (ws == null || session == null || !ws.isOpen()) {
            player.sendSystemMessage(ChatUtil.vb("Not connected. Run /vb connect first."));
            return 0;
        }

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

        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String url = String.format(
            "%s?player=%s&x=%d&y=%d&z=%d",
            IMAGE_INPUT_URL,
            encodedName,
            Math.round(x),
            Math.round(y),
            Math.round(z)
        );

        Component link = Component.literal("Open image upload page")
            .withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(url)))
            );

        player.sendSystemMessage(ChatUtil.vb("Upload an image to generate a build prompt:"));
        player.sendSystemMessage(ChatUtil.vbGray("The page includes a QR code so you can open it on your phone."));
        player.sendSystemMessage(Component.empty().append(ChatUtil.vb("")).append(link));
        return 1;
    }

    private static String resolveImageInputUrl() {
        String explicit = firstNonBlank(
            System.getProperty("vibebuild.imageInputUrl"),
            System.getenv("VIBEBUILD_IMAGE_INPUT_URL")
        );
        if (explicit != null) {
            return stripTrailingSlash(explicit);
        }

        String host = getLanIPv4Address();
        if (host == null) {
            host = "localhost";
        }

        return String.format("http://%s:8787/image-input", host);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String getLanIPv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }

                Enumeration<java.net.InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fallback to localhost.
        }
        return null;
    }
}
