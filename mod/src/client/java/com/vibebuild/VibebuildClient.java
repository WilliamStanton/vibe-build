package com.vibebuild;

import com.vibebuild.network.ActivatePreviewPayload;
import com.vibebuild.network.CancelPreviewPayload;
import com.vibebuild.network.PreviewReadyPayload;
import com.vibebuild.preview.GhostRenderer;
import com.vibebuild.preview.PlacementController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.core.BlockPos;

public class VibebuildClient implements ClientModInitializer {

    private static VibebuildClient INSTANCE;
    public static VibebuildClient getInstance() { return INSTANCE; }

    private PlacementController placementController;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        placementController = new PlacementController();
        placementController.registerTick();

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

    public PlacementController getPlacementController() { return placementController; }
}
