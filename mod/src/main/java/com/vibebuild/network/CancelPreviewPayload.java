package com.vibebuild.network;

import com.vibebuild.Vibebuild;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server→Client packet sent when the player runs /vb cancel during PREVIEWING.
 * Tells the client to deactivate the ghost preview.
 */
public record CancelPreviewPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CancelPreviewPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Vibebuild.MOD_ID, "cancel_preview"));

    public static final StreamCodec<FriendlyByteBuf, CancelPreviewPayload> CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, CancelPreviewPayload p) -> {
                        // No data to write
                    },
                    (FriendlyByteBuf buf) -> new CancelPreviewPayload()
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
