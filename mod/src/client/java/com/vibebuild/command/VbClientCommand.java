package com.vibebuild.command;

import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.network.ImagePromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side handler for /vb image.
 * Opens a native file dialog, reads the image, and sends it to the server.
 */
public class VbClientCommand {

    /**
     * Opens a file dialog on a background thread (TinyFD blocks the calling thread).
     * On success, sends the image bytes + prompt to the server via C2S packet.
     */
    public static void openImageDialog(String prompt) {
        CompletableFuture.runAsync(() -> {
            try {
                String path;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer filters = stack.mallocPointer(5);
                    filters.put(stack.UTF8("*.png"));
                    filters.put(stack.UTF8("*.jpg"));
                    filters.put(stack.UTF8("*.jpeg"));
                    filters.put(stack.UTF8("*.gif"));
                    filters.put(stack.UTF8("*.webp"));
                    filters.flip();

                    path = TinyFileDialogs.tinyfd_openFileDialog(
                            "Select an image for VibeBuild",
                            null,
                            filters,
                            "Image files (png, jpg, gif, webp)",
                            false
                    );
                }

                if (path == null) {
                    // User cancelled the dialog
                    Minecraft.getInstance().execute(() -> {
                        if (Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().player.displayClientMessage(
                                    ChatUtil.vb("Image selection cancelled."), false);
                        }
                    });
                    return;
                }

                Path filePath = Path.of(path);
                byte[] imageBytes = Files.readAllBytes(filePath);
                String mimeType = guessMimeType(filePath.getFileName().toString());

                Vibebuild.LOGGER.info("[VB] Sending image: {} ({} bytes, {})",
                        filePath.getFileName(), imageBytes.length, mimeType);

                Minecraft.getInstance().execute(() -> {
                    ClientPlayNetworking.send(new ImagePromptPayload(imageBytes, mimeType, prompt));
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                                ChatUtil.vb("Image sent! " + filePath.getFileName()), false);
                    }
                });

            } catch (IOException e) {
                Vibebuild.LOGGER.error("[VB] Failed to read image file", e);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                                ChatUtil.vbError("Failed to read image: " + e.getMessage()), false);
                    }
                });
            }
        });
    }

    private static String guessMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png"; // fallback
    }
}
