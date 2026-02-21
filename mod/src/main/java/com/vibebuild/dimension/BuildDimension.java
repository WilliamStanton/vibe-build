package com.vibebuild.dimension;

import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.session.BuildSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import java.util.Set;

/**
 * Manages the dedicated void dimension used for AI builds.
 *
 * The dimension is defined via data-pack JSON at:
 *   resources/data/vibe-build/dimension/build_world.json
 *
 * Build coordinates from the server map directly to world coords here
 * (origin is always 0,64,0).
 */
public class BuildDimension {

    public static final ResourceKey<Level> DIMENSION_KEY = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(Vibebuild.MOD_ID, "build_world")
    );

    /** Default build origin when no bounds are known yet. */
    private static final double DEFAULT_BUILD_X = 0.5;
    private static final double DEFAULT_BUILD_Y = 64.0;
    private static final double DEFAULT_BUILD_Z = 0.5;

    /** Spectator distance offset from the build center. */
    private static final double SPECTATOR_OFFSET = 30.0;
    private static final double SPECTATOR_HEIGHT_OFFSET = 15.0;

    private final MinecraftServer server;

    public BuildDimension(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Wipes the build dimension by setting all blocks in the previous build region to air.
     * Uses direct chunk-level block setting for speed.
     */
    public void resetBuildWorld(BuildSession session) {
        ServerLevel buildLevel = server.getLevel(DIMENSION_KEY);
        if (buildLevel == null) return;
        if (session.buildMin == null || session.buildMax == null) return;

        int minX = session.buildMin.getX(), maxX = session.buildMax.getX();
        int minY = session.buildMin.getY(), maxY = session.buildMax.getY();
        int minZ = session.buildMin.getZ(), maxZ = session.buildMax.getZ();

        net.minecraft.world.level.block.state.BlockState air =
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!buildLevel.getBlockState(pos).isAir()) {
                        buildLevel.setBlock(pos, air, 2);
                        count++;
                    }
                }
            }
        }

        Vibebuild.LOGGER.info("[VB] Reset build world: cleared {} blocks ({} -> {}) for {}",
                count, session.buildMin, session.buildMax, session.playerName);

        session.buildMin = null;
        session.buildMax = null;
    }

    public void teleportToBuildDimension(ServerPlayer player, BuildSession session) {
        // Save full original state
        session.originalDimension = player.level().dimension();
        session.originalX         = player.getX();
        session.originalY         = player.getY();
        session.originalZ         = player.getZ();
        session.originalYaw       = player.getYRot();
        session.originalPitch     = player.getXRot();
        session.originalGameMode  = player.gameMode.getGameModeForPlayer();

        ServerLevel buildLevel = server.getLevel(DIMENSION_KEY);
        if (buildLevel == null) {
            Vibebuild.LOGGER.error("[VB] Build dimension '{}' not found! Is the data-pack installed?",
                    DIMENSION_KEY.toString());
            player.sendSystemMessage(ChatUtil.vbError("Build dimension not found. Check server data-pack."));
            return;
        }

        // Compute spectator position facing the build
        double buildCenterX = DEFAULT_BUILD_X;
        double buildCenterY = DEFAULT_BUILD_Y;
        double buildCenterZ = DEFAULT_BUILD_Z;

        if (session.buildOrigin != null) {
            buildCenterX = session.buildOrigin.getX() + 0.5;
            buildCenterY = session.buildOrigin.getY();
            buildCenterZ = session.buildOrigin.getZ() + 0.5;
        }

        // Position the player offset to the south-west looking north-east at the build
        double spawnX = buildCenterX - SPECTATOR_OFFSET;
        double spawnY = buildCenterY + SPECTATOR_HEIGHT_OFFSET;
        double spawnZ = buildCenterZ + SPECTATOR_OFFSET;

        // Calculate yaw/pitch to look at build center
        float[] lookAngles = lookAt(spawnX, spawnY, spawnZ, buildCenterX, buildCenterY, buildCenterZ);

        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(buildLevel,
                spawnX, spawnY, spawnZ,
                Set.of(),
                lookAngles[0], lookAngles[1],
                false);
        Vibebuild.LOGGER.info("[VB] Teleported {} to build dimension (spectator)", session.playerName);
    }

    /**
     * Repositions the spectator to face a known origin point.
     * Called after the plan is ready so the player watches from the right spot.
     */
    public void repositionToFaceBuild(ServerPlayer player, BuildSession session, int originX, int originY, int originZ) {
        ServerLevel buildLevel = server.getLevel(DIMENSION_KEY);
        if (buildLevel == null) return;

        double cx = originX + 0.5;
        double cy = originY;
        double cz = originZ + 0.5;

        double offset = SPECTATOR_OFFSET;
        double spawnX = cx - offset * 0.7;
        double spawnY = cy + SPECTATOR_HEIGHT_OFFSET;
        double spawnZ = cz + offset * 0.7;

        float[] lookAngles = lookAt(spawnX, spawnY, spawnZ, cx, cy, cz);
        player.teleportTo(buildLevel, spawnX, spawnY, spawnZ, Set.of(), lookAngles[0], lookAngles[1], false);
    }

    /**
     * Repositions the spectator to face the build after bounds are known.
     * Called after the build completes so the player gets a good view.
     */
    public void repositionToFaceBuild(ServerPlayer player, BuildSession session) {
        if (session.buildMin == null || session.buildMax == null) return;

        ServerLevel buildLevel = server.getLevel(DIMENSION_KEY);
        if (buildLevel == null) return;

        // Center of the bounding box
        double cx = (session.buildMin.getX() + session.buildMax.getX()) / 2.0 + 0.5;
        double cy = (session.buildMin.getY() + session.buildMax.getY()) / 2.0;
        double cz = (session.buildMin.getZ() + session.buildMax.getZ()) / 2.0 + 0.5;

        // Build size for dynamic offset
        double sizeX = session.buildMax.getX() - session.buildMin.getX();
        double sizeZ = session.buildMax.getZ() - session.buildMin.getZ();
        double maxSpan = Math.max(sizeX, sizeZ);
        double offset = Math.max(20.0, maxSpan * 1.2);

        double spawnX = cx - offset * 0.7;
        double spawnY = cy + Math.max(10.0, maxSpan * 0.5);
        double spawnZ = cz + offset * 0.7;

        float[] lookAngles = lookAt(spawnX, spawnY, spawnZ, cx, cy, cz);

        player.teleportTo(buildLevel,
                spawnX, spawnY, spawnZ,
                Set.of(),
                lookAngles[0], lookAngles[1],
                false);
    }

    public void teleportBack(ServerPlayer player, BuildSession session) {
        if (session.originalDimension == null) return;

        ServerLevel originalLevel = server.getLevel(session.originalDimension);
        if (originalLevel == null) {
            originalLevel = server.overworld();
        }

        // Restore original game mode
        GameType mode = session.originalGameMode;
        if (mode == null) mode = GameType.SURVIVAL;

        player.setGameMode(mode);
        player.teleportTo(originalLevel,
                session.originalX, session.originalY, session.originalZ,
                Set.of(),
                session.originalYaw, session.originalPitch,
                false);

        session.originalDimension = null;
        Vibebuild.LOGGER.info("[VB] Teleported {} back to {}",
                session.playerName, originalLevel.dimension().toString());
    }

    // ── Helpers ──

    /** Compute yaw and pitch to look from (x1,y1,z1) toward (x2,y2,z2). */
    private static float[] lookAt(double x1, double y1, double z1,
                                  double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw   = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float)(Math.toDegrees(-Math.atan2(dy, dist)));
        return new float[]{yaw, pitch};
    }
}
