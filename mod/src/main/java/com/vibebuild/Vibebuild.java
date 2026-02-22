package com.vibebuild;

import com.vibebuild.command.VbCommand;
import com.vibebuild.dimension.BuildDimension;
import com.vibebuild.executor.ToolExecutor;
import com.vibebuild.network.ActivatePreviewPayload;
import com.vibebuild.network.CancelPreviewPayload;
import com.vibebuild.network.PreviewReadyPayload;
import com.vibebuild.network.VbWebSocketClient;
import com.vibebuild.schematic.SchematicManager;
import com.vibebuild.session.BuildSession;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Vibebuild implements ModInitializer {

    public static final String MOD_ID = "vibe-build";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static Vibebuild INSTANCE;
    public static Vibebuild getInstance() { return INSTANCE; }

    private final Map<String, BuildSession>       sessions   = new ConcurrentHashMap<>();
    private final Map<String, VbWebSocketClient>  webSockets = new ConcurrentHashMap<>();

    private MinecraftServer  server;
    private BuildDimension   buildDimension;
    private ToolExecutor     toolExecutor;
    private SchematicManager schematicManager;

    public Map<String, BuildSession>      getSessions()         { return sessions; }
    public Map<String, VbWebSocketClient> getWebSockets()       { return webSockets; }
    public MinecraftServer                getServer()           { return server; }
    public BuildDimension                 getBuildDimension()   { return buildDimension; }
    public ToolExecutor                   getToolExecutor()     { return toolExecutor; }
    public SchematicManager               getSchematicManager() { return schematicManager; }

    @Override
    public void onInitialize() {
        INSTANCE = this;

        toolExecutor     = new ToolExecutor();
        schematicManager = new SchematicManager();

        // Register the S2C payload types so the game knows how to encode/decode them
        PayloadTypeRegistry.playS2C().register(
                PreviewReadyPayload.TYPE,
                PreviewReadyPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                ActivatePreviewPayload.TYPE,
                ActivatePreviewPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                CancelPreviewPayload.TYPE,
                CancelPreviewPayload.CODEC
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VbCommand.register(dispatcher));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VbPasteCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            this.server         = s;
            this.buildDimension = new BuildDimension(s);
            LOGGER.info("[VB] vibe-build mod ready.");
        });

        // Auto-connect players to the WS server when they join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer player = handler.getPlayer();
            String name = player.getName().getString();
            if (sessions.containsKey(name)) return; // already connected

            try {
                BuildSession session = new BuildSession(name);
                VbWebSocketClient ws = new VbWebSocketClient(
                        new URI("ws://localhost:8080"),
                        () -> this.server.getPlayerList().getPlayerByName(name),
                        session
                );
                ws.connect();
                sessions.put(name, session);
                webSockets.put(name, ws);
                LOGGER.info("[VB] Auto-connecting {} to vibe-build server", name);
            } catch (Exception e) {
                LOGGER.warn("[VB] Auto-connect failed for {}: {}", name, e.getMessage());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            webSockets.values().forEach(ws -> { try { ws.closeBlocking(); } catch (Exception ignored) {} });
            webSockets.clear();
            sessions.clear();
        });

        LOGGER.info("[VB] vibe-build initialised.");
    }

    /** Sends the build bounds to the client so it can capture blocks for ghost preview. */
    public void sendBuildBoundsToClient(ServerPlayer player, BuildSession session) {
        if (session.buildMin == null || session.buildMax == null) {
            LOGGER.warn("[VB] Cannot send build bounds — no bounds recorded for {}", session.playerName);
            return;
        }
        ServerPlayNetworking.send(player,
                new PreviewReadyPayload(
                        session.buildMin.getX(), session.buildMin.getY(), session.buildMin.getZ(),
                        session.buildMax.getX(), session.buildMax.getY(), session.buildMax.getZ()
                ));
    }

    /** Tells the client to activate ghost preview using previously captured blocks. */
    public void activateClientPreview(ServerPlayer player) {
        ServerPlayNetworking.send(player,
                new ActivatePreviewPayload(player.getX(), player.getY(), player.getZ()));
    }
}
