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
 * Persists VibeBuild configuration (API key, model name) to a JSON file
 * in the Minecraft config directory.
 */
public class VibeBuildConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("vibe-build.json");

    private static String apiKey = "";
    private static String model = "claude-opus-4-6";

    public static String getApiKey() { return apiKey; }
    public static String getModel()  { return model; }

    public static void setApiKey(String key) {
        apiKey = key;
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
            if (obj.has("apiKey"))  apiKey = obj.get("apiKey").getAsString();
            if (obj.has("model"))   model  = obj.get("model").getAsString();
            Vibebuild.LOGGER.info("[VB] Config loaded (model={})", model);
        } catch (Exception e) {
            Vibebuild.LOGGER.warn("[VB] Failed to load config: {}", e.getMessage());
        }
    }

    private static void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("apiKey", apiKey);
            obj.addProperty("model", model);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj));
        } catch (IOException e) {
            Vibebuild.LOGGER.error("[VB] Failed to save config: {}", e.getMessage());
        }
    }
}
