package com.vibebuild.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.vibebuild.Vibebuild;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists VibeBuild configuration (API keys, model name) to a JSON file
 * in the Minecraft config directory.
 *
 * Supports multiple providers: Anthropic and OpenAI.
 * The provider is auto-detected from the model name.
 */
public class VibeBuildConfig {

    public enum Provider {
        ANTHROPIC, OPENAI
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("vibe-build.json");

    private static String anthropicApiKey = "";
    private static String openaiApiKey = "";
    private static String model = "claude-opus-4-6";

    public static String getAnthropicApiKey() { return anthropicApiKey; }
    public static String getOpenaiApiKey()    { return openaiApiKey; }
    public static String getModel()           { return model; }

    /**
     * Returns the API key for the current model's provider.
     */
    public static String getApiKey() {
        return getProvider() == Provider.OPENAI ? openaiApiKey : anthropicApiKey;
    }

    /**
     * Auto-detect provider from the model name.
     */
    public static Provider getProvider() {
        return detectProvider(model);
    }

    public static Provider detectProvider(String modelName) {
        if (modelName == null) return Provider.ANTHROPIC;
        String lower = modelName.toLowerCase();
        if (lower.startsWith("gpt-") || lower.startsWith("o1-") || lower.startsWith("o3-")
                || lower.startsWith("o4-") || lower.startsWith("chatgpt-")) {
            return Provider.OPENAI;
        }
        return Provider.ANTHROPIC;
    }

    public static void setAnthropicApiKey(String key) {
        anthropicApiKey = key;
        save();
    }

    public static void setOpenaiApiKey(String key) {
        openaiApiKey = key;
        save();
    }

    public static void setModel(String modelName) {
        model = modelName;
        save();
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String json = Files.readString(CONFIG_PATH);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            // Support old single-key format
            if (obj.has("apiKey") && !obj.has("anthropicApiKey")) {
                anthropicApiKey = obj.get("apiKey").getAsString();
            }
            if (obj.has("anthropicApiKey")) anthropicApiKey = obj.get("anthropicApiKey").getAsString();
            if (obj.has("openaiApiKey"))    openaiApiKey = obj.get("openaiApiKey").getAsString();
            if (obj.has("model"))           model = obj.get("model").getAsString();
            Vibebuild.LOGGER.info("[VB] Config loaded (model={}, provider={})", model, getProvider());
        } catch (Exception e) {
            Vibebuild.LOGGER.warn("[VB] Failed to load config: {}", e.getMessage());
        }
    }

    private static void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("anthropicApiKey", anthropicApiKey);
            obj.addProperty("openaiApiKey", openaiApiKey);
            obj.addProperty("model", model);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj));
        } catch (IOException e) {
            Vibebuild.LOGGER.error("[VB] Failed to save config: {}", e.getMessage());
        }
    }
}
