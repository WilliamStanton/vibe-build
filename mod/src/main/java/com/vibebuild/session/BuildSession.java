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

    public enum Phase {
        IDLE,
        CONNECTED,
        PLANNING,    // AI is producing a plan
        BUILDING,    // AI executor is running tool calls
        REVIEWING,   // build done, player reviewing in build world
        PREVIEWING   // player confirmed, back home, ghost preview active
    }

    // Player identity
    public final String playerName;

    // Current phase
    public volatile Phase phase = Phase.CONNECTED;

    // ── Pipeline control ──

    /** Set to true when the player cancels. Checked between tool calls / steps. */
    public volatile boolean cancelled = false;

    /** Prevent overlapping prompt runs on the same session. */
    public volatile boolean processingPrompt = false;

    /** Planner chat history — persists across prompts so the AI knows what it already built. */
    public final List<HistoryMessage> plannerHistory = new ArrayList<>();

    // ── Vibe world session state ──

    /** True while the player is inside the build dimension (from first prompt until confirm/cancel). */
    public boolean inVibeWorldSession = false;

    /** Saved once at session start, restored once at session end. */
    public ResourceKey<Level> originalDimension;
    public double originalX, originalY, originalZ;
    public float originalYaw, originalPitch;
    public GameType originalGameMode;

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
