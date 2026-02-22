package com.vibebuild.network;

import com.vibebuild.Vibebuild;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server→Client packet sent when the player runs /vb confirm.
 * Tells the client to activate the ghost preview using previously captured blocks.
 * Contains the player's position as the initial placement anchor.
 */
public record ActivatePreviewPayload(double x, double y, double z)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ActivatePreviewPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Vibebuild.MOD_ID, "activate_preview"));

    public static final StreamCodec<FriendlyByteBuf, ActivatePreviewPayload> CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, ActivatePreviewPayload p) -> {
                        buf.writeDouble(p.x());
                        buf.writeDouble(p.y());
                        buf.writeDouble(p.z());
                    },
                    (FriendlyByteBuf buf) -> new ActivatePreviewPayload(
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble()
                    )
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
