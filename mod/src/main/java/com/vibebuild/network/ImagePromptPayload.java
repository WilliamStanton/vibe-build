package com.vibebuild.network;

import com.vibebuild.Vibebuild;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client→Server packet: sends an image (as bytes) plus a text prompt
 * for the AI image-to-build pipeline.
 */
public record ImagePromptPayload(
        byte[] imageBytes,
        String mimeType,
        String prompt
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ImagePromptPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Vibebuild.MOD_ID, "image_prompt"));

    public static final StreamCodec<FriendlyByteBuf, ImagePromptPayload> CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, ImagePromptPayload p) -> {
                        buf.writeByteArray(p.imageBytes());
                        buf.writeUtf(p.mimeType());
                        buf.writeUtf(p.prompt());
                    },
                    (FriendlyByteBuf buf) -> new ImagePromptPayload(
                            buf.readByteArray(),
                            buf.readUtf(),
                            buf.readUtf()
                    )
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
