package com.vibebuild.preview;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles player input during ghost-preview mode.
 *
 *  Left-click  — confirm placement (sends /vb paste to server)
 *  R           — rotate ghost 90° clockwise
 *  [           — rotate ghost 90° counter-clockwise
 *  Page Up/Dn  — move ghost up / down one block
 */
public class PlacementController {

    private static final int LOOK_DIST = 40;

    private final Minecraft mc = Minecraft.getInstance();

    // Fabric key bindings for rotate (these go through the normal keybind system)
    private final KeyMapping keyRotateRight;
    private final KeyMapping keyRotateLeft;

    private GhostPreview ghost;
    private boolean active = false;

    /** Captured blocks from the build dimension, held here until ghost is activated after teleport. */
    private Map<BlockPos, BlockState> capturedBlocks;
    private int capturedSizeX, capturedSizeY, capturedSizeZ;

    // Raw key state tracking for PgUp/PgDn and left-click (bypass KeyMapping issues)
    private boolean pgUpWasDown = false;
    private boolean pgDownWasDown = false;
    private boolean lmbWasDown = false;

    public PlacementController() {
        keyRotateRight = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.vibe-build.rotate_right", GLFW.GLFW_KEY_R,
                KeyMapping.Category.MISC));
        keyRotateLeft  = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.vibe-build.rotate_left", GLFW.GLFW_KEY_LEFT_BRACKET,
                KeyMapping.Category.MISC));
    }

    public void registerTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active || ghost == null) return;

            // Snap placement to look target each tick
            if (client.player != null) {
                Vec3 eye  = client.player.getEyePosition(1.0f);
                Vec3 look = client.player.getLookAngle();
                ghost.snapToLook(eye, look, LOOK_DIST);
            }

            // Fabric key bindings for rotate
            while (keyRotateRight.consumeClick()) ghost.rotateRight();
            while (keyRotateLeft.consumeClick())  ghost.rotateLeft();

            // Raw GLFW polling for PgUp/PgDn (bypasses KeyMapping issues in spectator/preview)
            long window = mc.getWindow().handle();

            boolean pgUpDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_PAGE_UP) == GLFW.GLFW_PRESS;
            if (pgUpDown && !pgUpWasDown) ghost.adjustY(1);
            pgUpWasDown = pgUpDown;

            boolean pgDownDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_PAGE_DOWN) == GLFW.GLFW_PRESS;
            if (pgDownDown && !pgDownWasDown) ghost.adjustY(-1);
            pgDownWasDown = pgDownDown;

            // Raw GLFW polling for left mouse button (bypass KeyMapping.consumeClick issues)
            boolean lmbDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (lmbDown && !lmbWasDown) {
                confirmPlacement();
            }
            lmbWasDown = lmbDown;
        });
    }

    /**
     * Captures blocks from the build dimension while the player is still there.
     * Called when the PreviewReadyPayload is received during REVIEWING phase.
     */
    public void captureBlocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (mc.level == null) return;

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (!state.isAir()) {
                        // Store relative to min corner
                        BlockPos relPos = new BlockPos(x - minX, y - minY, z - minZ);
                        blocks.put(relPos, state);
                    }
                }
            }
        }

        this.capturedBlocks = blocks;
        this.capturedSizeX  = maxX - minX + 1;
        this.capturedSizeY  = maxY - minY + 1;
        this.capturedSizeZ  = maxZ - minZ + 1;

        com.vibebuild.Vibebuild.LOGGER.info("[VB] Captured {} blocks from build dimension ({}x{}x{})",
                blocks.size(), capturedSizeX, capturedSizeY, capturedSizeZ);
    }

    /**
     * Activates the ghost preview using previously captured blocks.
     * Called after the player is teleported back to their original world.
     */
    public void activateFromCapture(BlockPos startPos) {
        if (capturedBlocks == null || capturedBlocks.isEmpty()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(vbMsg("No blocks captured for preview."), false);
            }
            return;
        }

        this.ghost  = new GhostPreview(capturedBlocks, capturedSizeX, capturedSizeY, capturedSizeZ, startPos);
        this.active = true;
        this.capturedBlocks = null; // release reference

        if (mc.player != null) {
            mc.player.displayClientMessage(vbMsg(
                "Ghost preview active. Left-click to place, R to rotate, PgUp/PgDn to adjust height."
            ), false);
        }
    }

    /** Cancels preview mode without placing. */
    public void cancel() {
        this.ghost  = null;
        this.active = false;
        this.capturedBlocks = null;
        if (mc.player != null) {
            mc.player.displayClientMessage(vbMsg("Placement cancelled."), false);
        }
    }

    /** Returns true if captured blocks are ready to be used for ghost preview. */
    public boolean hasCapturedBlocks() {
        return capturedBlocks != null && !capturedBlocks.isEmpty();
    }

    public boolean isActive() { return active; }
    public GhostPreview getGhost() { return ghost; }

    // ── Helpers ──

    private static MutableComponent vbMsg(String text) {
        return Component.empty()
                .append(Component.literal("[VibeBuild] ").withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD))
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    // ── Placement ──

    private void confirmPlacement() {
        if (ghost == null || mc.player == null) return;

        BlockPos pos = ghost.placementPos;
        if (pos == null) return;

        // The corner is where relative (0,0,0) maps to in the world.
        // The WE clipboard origin is buildMin, so paste target = corner.
        BlockPos corner = ghost.getCornerPos();

        com.vibebuild.Vibebuild.LOGGER.info("[VB] Paste: center={} corner={} size={}x{}x{} rot={}",
                pos, corner, ghost.sizeX, ghost.sizeY, ghost.sizeZ, ghost.rotationSteps * 90);

        mc.player.connection.sendCommand(
                "vb paste " + corner.getX() + " " + corner.getY() + " " + corner.getZ()
                        + " " + (ghost.rotationSteps * 90)
        );

        active = false;
        ghost  = null;
    }
}
