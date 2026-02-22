package com.vibebuild.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Renders a translucent ghost of captured blocks at the current placement position.
 * Registered via WorldRenderEvents.END_MAIN in VibebuildClient.
 */
public class GhostRenderer {

    /**
     * Called every frame by the world render callback.
     */
    public static void render(WorldRenderContext ctx, GhostPreview ghost) {
        if (ghost == null || ghost.placementPos == null || ghost.blocks == null || ghost.blocks.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Corner position derived from center-bottom placement pos
        BlockPos corner = ghost.getCornerPos();

        // Center of rotation in relative coords (center of the build XZ, bottom Y=0)
        double centerRelX = (ghost.sizeX - 1) / 2.0;
        double centerRelZ = (ghost.sizeZ - 1) / 2.0;

        PoseStack poseStack = ctx.matrices();
        MultiBufferSource buffers = ctx.consumers();
        if (poseStack == null || buffers == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

        for (Map.Entry<BlockPos, BlockState> entry : ghost.blocks.entrySet()) {
            BlockPos relPos = entry.getKey();
            BlockState state = entry.getValue();

            double relX = relPos.getX();
            double relY = relPos.getY();
            double relZ = relPos.getZ();

            // Rotate around center of build
            double dx = relX - centerRelX;
            double dz = relZ - centerRelZ;
            double[] rotated = rotateXZ(dx, dz, ghost.rotationSteps);

            // Map back: corner + center + rotated offset
            int worldX = corner.getX() + (int) Math.round(rotated[0] + (ghost.getRotatedSizeX() - 1) / 2.0);
            int worldY = corner.getY() + (int) relY;
            int worldZ = corner.getZ() + (int) Math.round(rotated[1] + (ghost.getRotatedSizeZ() - 1) / 2.0);

            poseStack.pushPose();
            poseStack.translate(worldX, worldY, worldZ);

            dispatcher.renderSingleBlock(
                    state,
                    poseStack,
                    buffers,
                    0x00F000F0,   // full-bright lighting
                    0             // no overlay
            );

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    /** Rotate (dx, dz) around origin by 90-degree steps clockwise. */
    private static double[] rotateXZ(double dx, double dz, int steps) {
        for (int i = 0; i < steps; i++) {
            double tmp = dx;
            dx = -dz;
            dz = tmp;
        }
        return new double[]{dx, dz};
    }
}
