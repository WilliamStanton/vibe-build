package com.vibebuild.session;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * Holds all per-player state for a single vibe-build session.
 */
public class BuildSession {

    public enum Phase {
        IDLE,
        CONNECTED,
        PLANNING,    // server is producing a plan, player still in their world
        BUILDING,    // player is in the void build dimension watching the build
        REVIEWING,   // build done, player still in build world reviewing it
        PREVIEWING   // player confirmed, back home, ghost preview active
    }

    // Player identity
    public final String playerName;

    // Current phase
    public volatile Phase phase = Phase.CONNECTED;

    // Where the player was before we teleported them to the build dimension
    public ResourceKey<Level> originalDimension;
    public double originalX, originalY, originalZ;
    public float originalYaw, originalPitch;
    public GameType originalGameMode;

    // The region that was built in the build dimension (set when BUILDING starts)
    public BlockPos buildOrigin;   // matches plan.origin from the server
    public BlockPos buildMin;      // bounding box min (populated during build)
    public BlockPos buildMax;      // bounding box max (populated during build)

    public BuildSession(String playerName) {
        this.playerName = playerName;
    }
}
