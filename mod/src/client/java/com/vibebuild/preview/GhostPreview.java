package com.vibebuild.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Holds captured block data and current placement state for the ghost preview.
 * Block data is captured from the build dimension before teleporting back.
 *
 * placementPos is the CENTER-BOTTOM of the build (center of XZ, bottom of Y).
 * The renderer offsets by half the (rotated) size to go from center to corner.
 */
public class GhostPreview {

    /** Captured blocks relative to (0,0,0). Key = relative position, Value = block state. */
    public final Map<BlockPos, BlockState> blocks;

    /** Size of the captured build. */
    public final int sizeX, sizeY, sizeZ;

    /** Placement anchor: center-bottom of the build in world coords. */
    public volatile BlockPos placementPos;

    /** Rotation in 90-degree increments (0-3). */
    public volatile int rotationSteps = 0;

    /** Manual Y offset added by PgUp/PgDn, preserved across raycasts. */
    private int yOffset = 0;

    public GhostPreview(Map<BlockPos, BlockState> blocks, int sizeX, int sizeY, int sizeZ, BlockPos initialPos) {
        this.blocks = blocks;
        this.sizeX  = sizeX;
        this.sizeY  = sizeY;
        this.sizeZ  = sizeZ;
        this.placementPos = initialPos;
    }

    /** Rotate the ghost 90 degrees clockwise. */
    public void rotateRight() { rotationSteps = (rotationSteps + 1) % 4; }

    /** Rotate the ghost 90 degrees counter-clockwise. */
    public void rotateLeft() { rotationSteps = (rotationSteps + 3) % 4; }

    /** Move the ghost up or down by one block. */
    public void adjustY(int delta) {
        yOffset += delta;
        placementPos = placementPos.above(delta);
    }

    /**
     * Raycast from the player's eye position to find the first solid block,
     * then center the build on that hit point (XZ centered, Y on top of hit block).
     */
    public void snapToLook(Vec3 eyePos, Vec3 lookDir, int maxDist) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // Step along the ray in 0.5-block increments
        BlockPos lastAir = BlockPos.containing(eyePos);
        for (int i = 1; i <= maxDist * 2; i++) {
            double t = i * 0.5;
            BlockPos check = BlockPos.containing(
                    eyePos.x + lookDir.x * t,
                    eyePos.y + lookDir.y * t,
                    eyePos.z + lookDir.z * t);

            BlockState state = mc.level.getBlockState(check);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                // Found ground — place build centered on the block ABOVE the hit (lastAir)
                // so the build sits on the surface.
                placementPos = new BlockPos(lastAir.getX(), lastAir.getY() + yOffset, lastAir.getZ());
                return;
            }
            lastAir = check;
        }

        // No ground found — place at max distance
        BlockPos farBlock = BlockPos.containing(
                eyePos.x + lookDir.x * maxDist,
                eyePos.y + lookDir.y * maxDist,
                eyePos.z + lookDir.z * maxDist);
        placementPos = new BlockPos(farBlock.getX(), farBlock.getY() + yOffset, farBlock.getZ());
    }

    /** Get the effective rotated size X (accounting for rotation). */
    public int getRotatedSizeX() {
        return (rotationSteps % 2 == 0) ? sizeX : sizeZ;
    }

    /** Get the effective rotated size Z (accounting for rotation). */
    public int getRotatedSizeZ() {
        return (rotationSteps % 2 == 0) ? sizeZ : sizeX;
    }

    /** Get the corner position from the center-bottom placement pos. */
    public BlockPos getCornerPos() {
        int rsx = getRotatedSizeX();
        int rsz = getRotatedSizeZ();
        return new BlockPos(
                placementPos.getX() - rsx / 2,
                placementPos.getY(),
                placementPos.getZ() - rsz / 2
        );
    }
}
