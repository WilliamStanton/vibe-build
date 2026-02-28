package com.vibebuild;

import com.vibebuild.command.VbClientCommand;
import com.vibebuild.network.ActivatePreviewPayload;
import com.vibebuild.network.CancelPreviewPayload;
import com.vibebuild.network.OpenImageDialogPayload;
import com.vibebuild.network.PreviewReadyPayload;
import com.vibebuild.preview.GhostRenderer;
import com.vibebuild.preview.PlacementController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.core.BlockPos;

/**
 * Client-side mod entry point.
 *
 * Manages the ghost-preview flow: receives build-bounds and activate packets
 * from the server, drives {@link PlacementController} for left-click placement,
 * and renders ghost blocks each frame via {@link GhostRenderer}.
 */
public class VibebuildClient implements ClientModInitializer {

    private static VibebuildClient INSTANCE;
    /** Returns the singleton set during {@link #onInitializeClient()}. */
    public static VibebuildClient getInstance() { return INSTANCE; }

    /** Owns the captured block set and handles player input during ghost preview. */
    private PlacementController placementController;

    /** Registers packet handlers, the tick loop, and the per-frame render hook. */
    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        placementController = new PlacementController();
        placementController.registerTick();

        // Packet: Open image dialog — server tells client to open a file picker
        ClientPlayNetworking.registerGlobalReceiver(
                OpenImageDialogPayload.TYPE,
                (payload, ctx) -> {
                    ctx.client().execute(() -> {
                        VbClientCommand.openImageDialog(payload.prompt());
                    });
                }
        );

        // Packet 1: Build bounds — capture blocks from the build dimension while still there.
        // Received during REVIEWING phase before the player types /vb confirm.
        ClientPlayNetworking.registerGlobalReceiver(
                PreviewReadyPayload.TYPE,
                (payload, ctx) -> {
                    ctx.client().execute(() -> {
                        placementController.captureBlocks(
                                payload.minX(), payload.minY(), payload.minZ(),
                                payload.maxX(), payload.maxY(), payload.maxZ()
                        );
                    });
                }
        );

        // Packet 2: Activate preview — teleport is done, activate ghost with captured blocks.
        // Received after /vb confirm teleports the player back to their original world.
        ClientPlayNetworking.registerGlobalReceiver(
                ActivatePreviewPayload.TYPE,
                (payload, ctx) -> {
                    ctx.client().execute(() -> {
                        BlockPos startPos = BlockPos.containing(payload.x(), payload.y(), payload.z());
                        placementController.activateFromCapture(startPos);
                    });
                }
        );

        // Packet 3: Cancel preview — deactivate the ghost preview.
        ClientPlayNetworking.registerGlobalReceiver(
                CancelPreviewPayload.TYPE,
                (payload, ctx) -> {
                    ctx.client().execute(() -> {
                        placementController.cancel();
                    });
                }
        );

        // Render ghost each frame
        WorldRenderEvents.END_MAIN.register(ctx -> {
            if (placementController.isActive()) {
                GhostRenderer.render(ctx, placementController.getGhost());
            }
        });
    }

    /** Returns the active placement controller for use by client-side mixins or commands. */
    public PlacementController getPlacementController() { return placementController; }
}
