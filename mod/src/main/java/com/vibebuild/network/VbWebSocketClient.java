package com.vibebuild.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.session.BuildSession;
import net.minecraft.server.level.ServerPlayer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.function.Supplier;

/**
 * One WebSocket connection per player session.
 * Runs on a background thread; all Minecraft calls are dispatched to the server thread.
 */
public class VbWebSocketClient extends WebSocketClient {

    private static final Gson GSON = new Gson();

    private final Supplier<ServerPlayer> playerSupplier;
    private final BuildSession session;

    /** Accumulates delta text. Displayed only when text_content_complete arrives. */
    private final StringBuilder deltaBuffer = new StringBuilder();

    public VbWebSocketClient(URI uri, Supplier<ServerPlayer> playerSupplier, BuildSession session) {
        super(uri);
        this.playerSupplier = playerSupplier;
        this.session = session;
    }

    // ── Lifecycle ──

    @Override
    public void onOpen(ServerHandshake handshake) {
        Vibebuild.LOGGER.info("[VB] WebSocket connected for {}", session.playerName);
        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player != null) {
                player.sendSystemMessage(ChatUtil.vb("Connected to vibe-build server."));
            }
        });
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        Vibebuild.LOGGER.info("[VB] WebSocket closed for {} (code={} reason={})", session.playerName, code, reason);
        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player != null) {
                player.sendSystemMessage(ChatUtil.vb("Disconnected from vibe-build server."));
            }
            session.phase = BuildSession.Phase.IDLE;
        });
    }

    @Override
    public void onError(Exception ex) {
        Vibebuild.LOGGER.error("[VB] WebSocket error for {}: {}", session.playerName, ex.getMessage());
        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player != null) {
                player.sendSystemMessage(ChatUtil.vbError("Connection error: " + ex.getMessage()));
            }
        });
    }

    // ── Message handling ──

    @Override
    public void onMessage(String raw) {
        Vibebuild.LOGGER.debug("[VB] <- {}", raw);
        JsonObject msg;
        try {
            msg = GSON.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            Vibebuild.LOGGER.warn("[VB] Could not parse message: {}", raw);
            return;
        }

        String type = msg.has("type") ? msg.get("type").getAsString() : "";

        switch (type) {
            case "thinking"              -> handleThinking();
            case "plan_ready"            -> handlePlanReady(msg);
            case "step"                  -> handleStep(msg);
            case "delta"                 -> handleDelta(msg);
            case "text_content_complete" -> handleTextContentComplete(msg);
            case "tool_call"             -> handleToolCall(msg);
            case "done"                  -> handleDone(msg);
            case "error"                 -> handleError(msg);
            default                      -> Vibebuild.LOGGER.warn("[VB] Unknown message type: {}", type);
        }
    }

    // ── Handlers ──

    private void handleThinking() {
        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player == null) return;
            session.phase = BuildSession.Phase.PLANNING;
            player.sendSystemMessage(ChatUtil.vb("Planning your build..."));

            // Clear any leftover blocks from a previous build
            Vibebuild.getInstance().getBuildDimension().resetBuildWorld(session);

            // Teleport to build dimension immediately so the player can watch from the start
            Vibebuild.getInstance().getBuildDimension().teleportToBuildDimension(player, session);
        });
    }

    private void handlePlanReady(JsonObject msg) {
        JsonObject origin = msg.has("origin") ? msg.getAsJsonObject("origin") : null;
        int stepCount = msg.has("stepCount") ? msg.get("stepCount").getAsInt() : 0;
        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player == null) return;

            // Store origin so spectator repositions to face the build
            if (origin != null) {
                int ox = origin.get("x").getAsInt();
                int oy = origin.get("y").getAsInt();
                int oz = origin.get("z").getAsInt();
                session.buildOrigin = new net.minecraft.core.BlockPos(ox, oy, oz);
                Vibebuild.getInstance().getBuildDimension().repositionToFaceBuild(player, session, ox, oy, oz);
            }

            player.sendSystemMessage(ChatUtil.vb("Planning complete: " + stepCount + " features to build."));
        });
    }

    private void handleStep(JsonObject msg) {
        // A new step means flush any pending delta text first
        flushDeltaBuffer();

        String content = msg.has("content") ? msg.get("content").getAsString() : "";
        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player == null) return;

            // Switch to BUILDING on the first step (teleport already happened in handleThinking)
            if (session.phase == BuildSession.Phase.PLANNING) {
                session.phase = BuildSession.Phase.BUILDING;
            }

            player.sendSystemMessage(ChatUtil.vb(content));
        });
    }

    /** Delta just accumulates text; nothing is displayed until text_content_complete. */
    private void handleDelta(JsonObject msg) {
        String content = msg.has("content") ? msg.get("content").getAsString() : "";
        if (content.isEmpty()) return;
        synchronized (deltaBuffer) {
            deltaBuffer.append(content);
        }
    }

    /** Server signals a complete text message — display what we accumulated. */
    private void handleTextContentComplete(JsonObject msg) {
        // Prefer the content in the complete message; fall back to accumulated buffer
        String content = msg.has("content") ? msg.get("content").getAsString() : "";
        if (content.isBlank()) {
            synchronized (deltaBuffer) {
                content = deltaBuffer.toString().strip();
                deltaBuffer.setLength(0);
            }
        } else {
            synchronized (deltaBuffer) {
                deltaBuffer.setLength(0);
            }
        }
        if (content.isEmpty()) return;

        // Split on double-newlines into paragraphs and send each as a separate chat message
        String[] paragraphs = content.split("\n\n+");
        for (String para : paragraphs) {
            String cleaned = para.strip().replaceAll("\n", " ");
            if (cleaned.isEmpty()) continue;
            final String fCleaned = cleaned;
            runOnServerThread(() -> {
                ServerPlayer player = playerSupplier.get();
                if (player != null) {
                    player.sendSystemMessage(ChatUtil.vbGray(fCleaned));
                }
            });
        }
    }

    /** Flush and discard any accumulated delta text (used on step/done/error boundaries). */
    private void flushDeltaBuffer() {
        synchronized (deltaBuffer) {
            deltaBuffer.setLength(0);
        }
    }

    private void handleToolCall(JsonObject msg) {
        // Flush any pending delta before tool execution
        flushDeltaBuffer();

        String toolCallId = msg.has("toolCallId") ? msg.get("toolCallId").getAsString() : "";
        String name       = msg.has("name")       ? msg.get("name").getAsString()       : "";
        JsonObject args   = msg.has("args")        ? msg.get("args").getAsJsonObject()   : new JsonObject();

        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            JsonObject result = Vibebuild.getInstance()
                    .getToolExecutor()
                    .execute(player, session, name, args);

            // Track bounding box from set/fill/generation calls for schematic capture
            Vibebuild.getInstance().getToolExecutor().updateBounds(session, name, args);

            // If tool failed, notify the player
            boolean success = result.has("success") && result.get("success").getAsBoolean();
            if (!success && player != null) {
                String errMsg = result.has("message") ? result.get("message").getAsString() : "unknown";
                player.sendSystemMessage(ChatUtil.vbError("Tool " + name + " failed: " + errMsg));
            }

            // Send result back to the server (model sees errors and can self-correct)
            JsonObject reply = new JsonObject();
            reply.addProperty("type", "tool_result");
            reply.addProperty("toolCallId", toolCallId);
            reply.addProperty("result", GSON.toJson(result));
            send(GSON.toJson(reply));
        });
    }

    private void handleDone(JsonObject msg) {
        // Flush remaining delta text
        flushDeltaBuffer();

        int toolCount      = msg.has("toolCount")      ? msg.get("toolCount").getAsInt()      : 0;
        int completedSteps = msg.has("completedSteps") ? msg.get("completedSteps").getAsInt() : 0;

        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player == null) return;

            // Save schematic from the build region
            boolean saved = Vibebuild.getInstance()
                    .getSchematicManager()
                    .saveBuildToClipboard(player, session);

            // Stay in the build dimension so the player can review the build
            session.phase = BuildSession.Phase.REVIEWING;

            // Reposition to face the completed build
            Vibebuild.getInstance().getBuildDimension().repositionToFaceBuild(player, session);

            String summary = String.format(
                "Build complete! %d steps, %d commands.",
                completedSteps, toolCount
            );
            player.sendSystemMessage(ChatUtil.vb(summary));

            if (saved) {
                // Send build bounds to the client so it can capture blocks for ghost preview
                Vibebuild.getInstance().sendBuildBoundsToClient(player, session);

                player.sendSystemMessage(ChatUtil.vb("Fly around to review your build."));
                player.sendSystemMessage(ChatUtil.vb("Type /vb confirm to accept and place it in your world."));
                player.sendSystemMessage(ChatUtil.vb("Type /vb cancel to discard and return."));
            } else {
                player.sendSystemMessage(ChatUtil.vbError("Could not save schematic. Use /vb cancel to return."));
            }
        });
    }

    private void handleError(JsonObject msg) {
        // Flush remaining delta text
        flushDeltaBuffer();

        String content = msg.has("content") ? msg.get("content").getAsString() : "unknown error";
        runOnServerThread(() -> {
            ServerPlayer player = playerSupplier.get();
            if (player == null) return;
            session.phase = BuildSession.Phase.CONNECTED;
            player.sendSystemMessage(ChatUtil.vbError(content));
            // Teleport back if we were in the build dimension
            Vibebuild.getInstance().getBuildDimension().teleportBack(player, session);
        });
    }

    // ── Helpers ──

    /** Dispatches work to the main server thread safely. */
    private void runOnServerThread(Runnable r) {
        Vibebuild.getInstance().getServer().execute(r);
    }

    /** Send a prompt message to the vibe-build server. */
    public void sendPrompt(String content, double x, double y, double z) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "prompt");
        msg.addProperty("content", content);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        msg.add("playerPosition", pos);
        send(GSON.toJson(msg));
    }

    /** Send a cancel message to the vibe-build server. */
    public void sendCancel() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "cancel");
        send(GSON.toJson(msg));
    }
}
