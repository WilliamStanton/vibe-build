package com.vibebuild.network;

import com.vibebuild.Vibebuild;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server→Client packet sent when the build is complete and the client should
 * capture the blocks from the build dimension for ghost preview.
 *
 * Contains the bounding box of the build (min/max corners).
 */
public record PreviewReadyPayload(
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PreviewReadyPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Vibebuild.MOD_ID, "preview_ready"));

    public static final StreamCodec<FriendlyByteBuf, PreviewReadyPayload> CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, PreviewReadyPayload p) -> {
                        buf.writeInt(p.minX());
                        buf.writeInt(p.minY());
                        buf.writeInt(p.minZ());
                        buf.writeInt(p.maxX());
                        buf.writeInt(p.maxY());
                        buf.writeInt(p.maxZ());
                    },
                    (FriendlyByteBuf buf) -> new PreviewReadyPayload(
                            buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readInt(), buf.readInt(), buf.readInt()
                    )
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
