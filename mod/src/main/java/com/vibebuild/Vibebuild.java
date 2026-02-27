package com.vibebuild;

import com.vibebuild.command.VbCommand;
import com.vibebuild.config.VibeBuildConfig;
import com.vibebuild.dimension.BuildDimension;
import com.vibebuild.executor.ToolExecutor;
import com.vibebuild.ai.BuildPipeline;
import com.vibebuild.network.ActivatePreviewPayload;
import com.vibebuild.network.CancelPreviewPayload;
import com.vibebuild.network.ImagePromptPayload;
import com.vibebuild.network.OpenImageDialogPayload;
import com.vibebuild.network.PreviewReadyPayload;
import com.vibebuild.schematic.SchematicManager;
import com.vibebuild.session.BuildSession;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Vibebuild implements ModInitializer {

    public static final String MOD_ID = "vibe-build";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static Vibebuild INSTANCE;
    public static Vibebuild getInstance() { return INSTANCE; }

    private final Map<String, BuildSession> sessions = new ConcurrentHashMap<>();

    private MinecraftServer  server;
    private BuildDimension   buildDimension;
    private ToolExecutor     toolExecutor;
    private SchematicManager schematicManager;

    public Map<String, BuildSession> getSessions()         { return sessions; }
    public MinecraftServer           getServer()           { return server; }
    public BuildDimension            getBuildDimension()   { return buildDimension; }
    public ToolExecutor              getToolExecutor()     { return toolExecutor; }
    public SchematicManager          getSchematicManager() { return schematicManager; }

    @Override
    public void onInitialize() {
        INSTANCE = this;

        VibeBuildConfig.load();
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
        PayloadTypeRegistry.playS2C().register(
                OpenImageDialogPayload.TYPE,
                OpenImageDialogPayload.CODEC
        );

        // C2S: image prompt from client
        PayloadTypeRegistry.playC2S().register(
                ImagePromptPayload.TYPE,
                ImagePromptPayload.CODEC
        );
        ServerPlayNetworking.registerGlobalReceiver(
                ImagePromptPayload.TYPE,
                (payload, ctx) -> {
                    ServerPlayer player = ctx.player();
                    String name = player.getName().getString();
                    BuildSession session = sessions.get(name);
                    if (session == null) {
                        player.sendSystemMessage(ChatUtil.vbError("No active session."));
                        return;
                    }
                    if (session.phase != BuildSession.Phase.CONNECTED && session.phase != BuildSession.Phase.REVIEWING) {
                        player.sendSystemMessage(ChatUtil.vb("Busy — wait for the current build to finish, or /vb cancel."));
                        return;
                    }

                    // Determine player position
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

                    net.minecraft.core.BlockPos playerPos = new net.minecraft.core.BlockPos(
                            (int) Math.round(x),
                            (int) Math.round(y),
                            (int) Math.round(z)
                    );

                    player.sendSystemMessage(ChatUtil.vb("Image received! Starting build..."));
                    BuildPipeline.runAsync(player, session, payload.prompt(),
                            payload.imageBytes(), payload.mimeType(), playerPos);
                }
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

        // Create a session for each player on join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer player = handler.getPlayer();
            String name = player.getName().getString();
            if (!sessions.containsKey(name)) {
                sessions.put(name, new BuildSession(name));
                LOGGER.info("[VB] Session created for {}", name);
            }
        });

        // Clean up sessions on disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            String name = handler.getPlayer().getName().getString();
            BuildSession session = sessions.remove(name);
            if (session != null) {
                session.cancelled = true; // stop any running pipeline
                LOGGER.info("[VB] Session removed for {}", name);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            sessions.values().forEach(session -> session.cancelled = true);
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
