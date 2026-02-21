package com.vibebuild;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Utility for formatted VibeBuild chat messages.
 *
 * Prefix: [VibeBuild] in bold gold
 * Body:   white text
 */
public final class ChatUtil {

    private static final MutableComponent PREFIX =
            Component.literal("[VibeBuild] ")
                    .withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD);

    private ChatUtil() {}

    /** Creates a prefixed chat message: [VibeBuild] <text> */
    public static MutableComponent vb(String text) {
        return Component.empty()
                .append(PREFIX.copy())
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    /** Creates a prefixed chat message with gray body (for less important info). */
    public static MutableComponent vbGray(String text) {
        return Component.empty()
                .append(PREFIX.copy())
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /** Creates a prefixed error message: [VibeBuild] <text> in red */
    public static MutableComponent vbError(String text) {
        return Component.empty()
                .append(PREFIX.copy())
                .append(Component.literal(text).withStyle(ChatFormatting.RED));
    }
}
