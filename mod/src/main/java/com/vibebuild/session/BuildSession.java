package com.vibebuild.session;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds all per-player state for a single vibe-build session.
 *
 * A "vibe world session" begins when the player sends a prompt from the overworld
 * and ends only when they type /vb confirm or /vb cancel.
 * During a session the player stays in the build dimension and can reprompt freely.
 */
public class BuildSession {

    /**
     * State machine for a player's build lifecycle.
     *
     * Typical transitions:
     *   IDLE → CONNECTED (player joins)
     *   CONNECTED → PLANNING → BUILDING (pipeline starts)
     *   BUILDING → REVIEWING (build done, player can inspect)
     *   REVIEWING → PREVIEWING (player types /vb confirm, teleported back)
     *   Any state → CONNECTED (player types /vb cancel)
     */
    public enum Phase {
        IDLE,
        CONNECTED,
        PLANNING,    // AI is producing a plan
        BUILDING,    // AI executor is running tool calls
        REVIEWING,   // build done, player reviewing in build world
        PREVIEWING   // player confirmed, back home, ghost preview active
    }

    /**
     * Which AI pipeline is currently active. Drives feedback text in
     * {@link com.vibebuild.command.VbCommand} (e.g. "build" vs "circuit").
     */
    public enum AgentType {
        BUILD,
        REDSTONE
    }

    /** Immutable player name; used as the session map key in {@link com.vibebuild.Vibebuild}. */
    public final String playerName;

    /** Current lifecycle phase. Written from pipeline threads; read from server thread. */
    public volatile Phase phase = Phase.CONNECTED;

    /** Which pipeline last ran. Updated before each pipeline starts so cancel/status UX is correct. */
    public volatile AgentType activeAgent = AgentType.BUILD;

    // ── Pipeline control ──

    /** Set to true when the player cancels. Checked between tool calls / steps. */
    public volatile boolean cancelled = false;

    /** Prevent overlapping prompt runs on the same session. */
    public volatile boolean processingPrompt = false;

    /** Planner chat history — persists across prompts so the AI knows what it already built. */
    public final List<HistoryMessage> plannerHistory = new ArrayList<>();

    /** Redstone planner chat history — persists across prompts for redstone circuit refinement. */
    public final List<HistoryMessage> redstonePlannerHistory = new ArrayList<>();

    // ── Vibe world session state ──

    /** True while the player is inside the build dimension (from first prompt until confirm/cancel). */
    public boolean inVibeWorldSession = false;

    /** Player's dimension, position, orientation, and game mode before entering the build dimension. Restored on confirm or cancel. */
    public ResourceKey<Level> originalDimension;
    public double originalX, originalY, originalZ;
    public float originalYaw, originalPitch;
    public GameType originalGameMode;

    // ── Image prompt (for current build only) ──

    /** Base64-encoded image data for the current build, or null if text-only. */
    public String imageBase64;
    /** MIME type of the image (e.g. "image/png"), or null. */
    public String imageMimeType;

    // ── Build state ──

    public BlockPos buildOrigin;   // matches plan.origin from the AI
    public BlockPos buildMin;      // bounding box min (populated during build)
    public BlockPos buildMax;      // bounding box max (populated during build)

    /** True after the first reposition to face the build. Prevents repeated teleports. */
    public boolean hasBeenPositioned = false;

    public BuildSession(String playerName) {
        this.playerName = playerName;
    }

    /**
     * Simple role+content message for planner history.
     */
    public static class HistoryMessage {
        public final String role;
        public final String content;
        public HistoryMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
