package com.vibebuild.network;

import com.vibebuild.Vibebuild;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server→Client packet: tells the client to open a file dialog for image selection.
 * Contains the text prompt to use alongside the image.
 */
public record OpenImageDialogPayload(
        String prompt
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenImageDialogPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Vibebuild.MOD_ID, "open_image_dialog"));

    public static final StreamCodec<FriendlyByteBuf, OpenImageDialogPayload> CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, OpenImageDialogPayload p) -> {
                        buf.writeUtf(p.prompt());
                    },
                    (FriendlyByteBuf buf) -> new OpenImageDialogPayload(
                            buf.readUtf()
                    )
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
