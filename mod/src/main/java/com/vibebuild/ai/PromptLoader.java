package com.vibebuild.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads packaged prompt resources from {@code src/main/resources/prompts}.
 *
 * Callers provide a path relative to that root, for example
 * {@code build/planner.txt} or {@code redstone/executor.txt}.
 */
public final class PromptLoader {

    private PromptLoader() {}

    /**
     * Read a UTF-8 prompt file from the classpath.
     *
     * @param relativePath path relative to {@code prompts/}
     * @return full prompt text
     */
    public static String load(String relativePath) {
        String path = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        String resourcePath = "prompts/" + path;

        try (InputStream is = PromptLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Prompt not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt: " + resourcePath, e);
        }
    }
}
