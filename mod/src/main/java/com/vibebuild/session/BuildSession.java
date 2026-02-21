package com.vibebuild.session;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

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
        PLANNING,    // server is producing a plan
        BUILDING,    // executor is running tool calls
        REVIEWING,   // build done, player reviewing in build world
        PREVIEWING   // player confirmed, back home, ghost preview active
    }

    // Player identity
    public final String playerName;

    // Current phase
    public volatile Phase phase = Phase.CONNECTED;

    // ── Vibe world session state ──

    /** True while the player is inside the build dimension (from first prompt until confirm/cancel). */
    public boolean inVibeWorldSession = false;

    /** Saved once at session start, restored once at session end. */
    public ResourceKey<Level> originalDimension;
    public double originalX, originalY, originalZ;
    public float originalYaw, originalPitch;
    public GameType originalGameMode;

    // ── Build state ──

    public BlockPos buildOrigin;   // matches plan.origin from the server
    public BlockPos buildMin;      // bounding box min (populated during build)
    public BlockPos buildMax;      // bounding box max (populated during build)

    /** True after the first reposition to face the build. Prevents repeated teleports. */
    public boolean hasBeenPositioned = false;

    public BuildSession(String playerName) {
        this.playerName = playerName;
    }
}
