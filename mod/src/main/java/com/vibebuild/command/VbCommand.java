package com.vibebuild.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.ai.BuildPipeline;
import com.vibebuild.config.VibeBuildConfig;
import com.vibebuild.network.CancelPreviewPayload;
import com.vibebuild.network.OpenImageDialogPayload;
import com.vibebuild.session.BuildSession;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Registers the /vb command tree.
 *
 * /vb apikey anthropic <key> — set Anthropic API key
 * /vb apikey openai <key>    — set OpenAI API key
 * /vb model [name]           — get/set model (auto-detects provider)
 * /vb image [prompt]         — open file dialog for image-to-build
 * /vb cancel                 — cancel current build and teleport back
 * /vb confirm                — accept reviewed build and return to place it
 * /vb <prompt...>            — send a build prompt to the AI
 */
public class VbCommand {

    // Cached model list from provider APIs
    private static List<String> cachedModels = List.of();
    private static long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private static final SuggestionProvider<CommandSourceStack> MODEL_SUGGESTIONS = (ctx, builder) -> {
        long now = System.currentTimeMillis();
        if (!cachedModels.isEmpty() && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return suggestMatching(builder, cachedModels);
        }

        // Fetch async so we don't block the server thread
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> models = fetchAllModels();
                cachedModels = models;
                cacheTimestamp = System.currentTimeMillis();
                return suggestMatchingSync(builder, models);
            } catch (Exception e) {
                Vibebuild.LOGGER.warn("[VB] Failed to fetch models for autocomplete: {}", e.getMessage());
                return builder.build();
            }
        });
    };

    private static CompletableFuture<Suggestions> suggestMatching(SuggestionsBuilder builder, List<String> options) {
        return CompletableFuture.completedFuture(suggestMatchingSync(builder, options));
    }

    private static Suggestions suggestMatchingSync(SuggestionsBuilder builder, List<String> options) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String option : options) {
            if (option.toLowerCase().startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.build();
    }

    private static List<String> fetchAllModels() {
        List<String> all = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();

        // Fetch Anthropic models
        String anthropicKey = VibeBuildConfig.getAnthropicApiKey();
        if (anthropicKey != null && !anthropicKey.isBlank()) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.anthropic.com/v1/models"))
                        .header("X-Api-Key", anthropicKey)
                        .header("anthropic-version", "2023-06-01")
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (json.has("data")) {
                    for (JsonElement el : json.getAsJsonArray("data")) {
                        JsonObject m = el.getAsJsonObject();
                        if (m.has("id")) all.add(m.get("id").getAsString());
                    }
                }
            } catch (Exception e) {
                Vibebuild.LOGGER.warn("[VB] Failed to fetch Anthropic models: {}", e.getMessage());
            }
        }

        // Fetch OpenAI models
        String openaiKey = VibeBuildConfig.getOpenaiApiKey();
        if (openaiKey != null && !openaiKey.isBlank()) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.openai.com/v1/models"))
                        .header("Authorization", "Bearer " + openaiKey)
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (json.has("data")) {
                    for (JsonElement el : json.getAsJsonArray("data")) {
                        JsonObject m = el.getAsJsonObject();
                        if (m.has("id")) {
                            String id = m.get("id").getAsString();
                            if (id.startsWith("gpt-")) all.add(id);
                        }
                    }
                }
            } catch (Exception e) {
                Vibebuild.LOGGER.warn("[VB] Failed to fetch OpenAI models: {}", e.getMessage());
            }
        }

        all.sort(String::compareTo);
        return all;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("vb")
                .requires(CommandSourceStack::isPlayer)

                // /vb apikey anthropic <key>
                // /vb apikey openai <key>
                .then(Commands.literal("apikey")
                    .then(Commands.literal("anthropic")
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                            .executes(ctx -> apikey(ctx, "anthropic"))))
                    .then(Commands.literal("openai")
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                            .executes(ctx -> apikey(ctx, "openai")))))

                // /vb model [name]
                .then(Commands.literal("model")
                    .executes(VbCommand::modelGet)
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(MODEL_SUGGESTIONS)
                        .executes(VbCommand::modelSet)))

                // /vb image [prompt]
                .then(Commands.literal("image")
                    .executes(ctx -> image(ctx, "Build what you see in this image"))
                    .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> image(ctx, StringArgumentType.getString(ctx, "prompt")))))

                // /vb cancel
                .then(Commands.literal("cancel")
                    .executes(VbCommand::cancel))

                // /vb confirm
                .then(Commands.literal("confirm")
                    .executes(VbCommand::confirm))

                // /vb <prompt...>
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                    .executes(VbCommand::prompt))
        );
    }

    // ── Handlers ──

    private static int apikey(CommandContext<CommandSourceStack> ctx, String provider) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String key = StringArgumentType.getString(ctx, "key");

        if (provider.equals("anthropic")) {
            VibeBuildConfig.setAnthropicApiKey(key);
        } else {
            VibeBuildConfig.setOpenaiApiKey(key);
        }

        // Invalidate model cache so new provider's models show up
        cachedModels = List.of();
        cacheTimestamp = 0;

        // Mask the key in chat for safety
        String masked = key.length() > 8
                ? key.substring(0, 4) + "..." + key.substring(key.length() - 4)
                : "****";
        player.sendSystemMessage(ChatUtil.vb(provider + " API key set: " + masked));
        return 1;
    }

    private static int image(CommandContext<CommandSourceStack> ctx, String prompt) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        // Send S2C packet telling the client to open a file dialog
        ServerPlayNetworking.send(player, new OpenImageDialogPayload(prompt));
        return 1;
    }

    private static int modelGet(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String provider = VibeBuildConfig.getProvider().name().toLowerCase();
        player.sendSystemMessage(ChatUtil.vb("Current model: " + VibeBuildConfig.getModel() + " (" + provider + ")"));
        return 1;
    }

    private static int modelSet(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = StringArgumentType.getString(ctx, "name");
        VibeBuildConfig.Provider provider = VibeBuildConfig.detectProvider(name);
        VibeBuildConfig.setModel(name);
        player.sendSystemMessage(ChatUtil.vb("Model set to: " + name + " (" + provider.name().toLowerCase() + ")"));
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();
        BuildSession session = Vibebuild.getInstance().getSessions().get(name);

        if (session == null) {
            player.sendSystemMessage(ChatUtil.vb("No active session."));
            return 0;
        }

        if (session.phase == BuildSession.Phase.CONNECTED || session.phase == BuildSession.Phase.IDLE) {
            player.sendSystemMessage(ChatUtil.vb("Nothing to cancel."));
            return 0;
        }

        // If building/planning, signal the pipeline to stop
        if (session.phase == BuildSession.Phase.BUILDING || session.phase == BuildSession.Phase.PLANNING) {
            session.cancelled = true;
        }

        // If previewing, tell the client to deactivate the ghost
        if (session.phase == BuildSession.Phase.PREVIEWING) {
            ServerPlayNetworking.send(player, new CancelPreviewPayload());
        }

        // Teleport back from build dimension (no-op if already back)
        Vibebuild.getInstance().getBuildDimension().teleportBack(player, session);

        session.phase = BuildSession.Phase.CONNECTED;

        player.sendSystemMessage(ChatUtil.vb("Build cancelled. Returned to your world."));
        Vibebuild.LOGGER.info("[VB] {} cancelled their build", name);
        return 1;
    }

    private static int confirm(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name = player.getName().getString();
        BuildSession session = Vibebuild.getInstance().getSessions().get(name);

        if (session == null) {
            player.sendSystemMessage(ChatUtil.vb("No active session."));
            return 0;
        }

        if (session.phase != BuildSession.Phase.REVIEWING) {
            player.sendSystemMessage(ChatUtil.vb("Nothing to confirm. Build something first with /vb <prompt>."));
            return 0;
        }

        // Clear the build area in the build dimension before leaving
        Vibebuild.getInstance().getBuildDimension().clearBuildArea(session);

        // Teleport back to original world
        Vibebuild.getInstance().getBuildDimension().teleportBack(player, session);

        session.phase = BuildSession.Phase.PREVIEWING;

        player.sendSystemMessage(ChatUtil.vb("Build confirmed! Use the ghost preview to place it."));
        player.sendSystemMessage(ChatUtil.vb("Left-click to place, R to rotate, PgUp/PgDn to adjust height."));

        Vibebuild.getInstance().activateClientPreview(player);

        Vibebuild.LOGGER.info("[VB] {} confirmed their build", name);
        return 1;
    }

    private static int prompt(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String name   = player.getName().getString();
        String prompt = StringArgumentType.getString(ctx, "prompt");

        BuildSession session = Vibebuild.getInstance().getSessions().get(name);

        if (session == null) {
            player.sendSystemMessage(ChatUtil.vbError("No active session. Rejoin the server."));
            return 0;
        }

        if (session.phase != BuildSession.Phase.CONNECTED && session.phase != BuildSession.Phase.REVIEWING) {
            player.sendSystemMessage(ChatUtil.vb("Busy — wait for the current build to finish, or /vb cancel."));
            return 0;
        }

        // Determine player position (use saved pos if already in build dimension)
        double x, y, z;
        if (session.inVibeWorldSession) {
            x = session.originalX;
            y = session.originalY;
            z = session.originalZ;
        } else {
            x = player.getX();
            y = player.getY();
            z = player.getZ();
        }

        BlockPos playerPos = new BlockPos(
                (int) Math.round(x),
                (int) Math.round(y),
                (int) Math.round(z)
        );

        player.sendSystemMessage(ChatUtil.vb("Sending: " + prompt));

        // Launch the AI pipeline on a background thread
        BuildPipeline.runAsync(player, session, prompt, playerPos);
        return 1;
    }
}
