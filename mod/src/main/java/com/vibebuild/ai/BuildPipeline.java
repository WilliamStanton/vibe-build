package com.vibebuild.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.config.VibeBuildConfig;
import com.vibebuild.session.BuildSession;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Orchestrates the three-stage AI build pipeline entirely within the mod:
 * Planner → Executor → Finalizer.
 *
 * Runs on a background thread. Tool calls are dispatched to the server thread.
 */
public class BuildPipeline {

    private static final Gson GSON = new Gson();

    /**
     * Start the pipeline asynchronously for the given player and prompt.
     */
    public static void runAsync(ServerPlayer player, BuildSession session, String prompt, BlockPos playerPos) {
        if (session.processingPrompt) {
            player.sendSystemMessage(ChatUtil.vbError("Build already in progress. Wait or /vb cancel."));
            return;
        }
        session.processingPrompt = true;
        session.cancelled = false;

        final String playerName = player.getName().getString();

        CompletableFuture.runAsync(() -> {
            try {
                run(playerName, session, prompt, playerPos);
            } catch (Throwable e) {
                Vibebuild.LOGGER.error("[VB] Pipeline error: {} - {}", e.getClass().getName(), e.getMessage(), e);
                runOnServerThread(() -> {
                    ServerPlayer p = getPlayer(playerName);
                    if (p != null) {
                        String errMsg = e.getClass().getSimpleName()
                                + (e.getMessage() != null ? ": " + e.getMessage() : "");
                        p.sendSystemMessage(ChatUtil.vbError(errMsg));
                    }
                    if (session.inVibeWorldSession) {
                        session.phase = BuildSession.Phase.REVIEWING;
                        if (p != null) p.sendSystemMessage(ChatUtil.vb("You can reprompt or /vb cancel to return."));
                    } else {
                        session.phase = BuildSession.Phase.CONNECTED;
                    }
                });
            } finally {
                session.processingPrompt = false;
            }
        });
    }

    private static void run(String playerName, BuildSession session, String prompt, BlockPos playerPos) {
        String apiKey = VibeBuildConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("No API key set. Use /vb apikey <key> to set one.");
        }

        Vibebuild.LOGGER.info("[VB] Building ChatModel with model='{}' apiKey={}...",
                VibeBuildConfig.getModel(), apiKey.substring(0, Math.min(8, apiKey.length())) + "...");
        ChatModel model;
        try {
            model = AnthropicChatModel.builder()
                    .httpClientBuilder(new JdkHttpClientBuilder())
                    .apiKey(apiKey)
                    .modelName(VibeBuildConfig.getModel())
                    .maxTokens(16384)
                    .timeout(Duration.ofHours(1))
                    .build();
            Vibebuild.LOGGER.info("[VB] ChatModel built successfully");
        } catch (Exception e) {
            Vibebuild.LOGGER.error("[VB] Failed to build ChatModel: {} - {}", e.getClass().getName(), e.getMessage(), e);
            throw e;
        }

        // ── Teleport to build dimension ──
        runOnServerThreadAndWait(() -> {
            ServerPlayer player = getPlayer(playerName);
            if (player == null) throw new RuntimeException("Player disconnected");

            session.phase = BuildSession.Phase.PLANNING;
            player.sendSystemMessage(ChatUtil.vb("Planning your build..."));

            if (!session.inVibeWorldSession) {
                session.inVibeWorldSession = true;
                session.hasBeenPositioned = false;
                session.buildMin = null;
                session.buildMax = null;
                Vibebuild.getInstance().getBuildDimension().savePlayerState(player, session);
                Vibebuild.getInstance().getBuildDimension().teleportToBuildDimension(player, session);
            }
        });

        long t0 = System.currentTimeMillis();

        // ── STAGE 1: PLANNER ──
        Vibebuild.LOGGER.info("[VB] [PLANNER] Starting for '{}' with model '{}'", prompt, VibeBuildConfig.getModel());
        Plan plan = runPlanner(model, session, prompt, playerPos);
        Vibebuild.LOGGER.info("[VB] [PLANNER] Done in {}ms — Plan: '{}' with {} steps",
                System.currentTimeMillis() - t0, plan.planTitle, plan.steps.size());

        checkCancelled(session);

        runOnServerThreadAndWait(() -> {
            ServerPlayer player = getPlayer(playerName);
            if (player == null) return;

            if (!session.hasBeenPositioned) {
                session.buildOrigin = new BlockPos(plan.origin.x, plan.origin.y, plan.origin.z);
                Vibebuild.getInstance().getBuildDimension().repositionToFaceBuild(
                        player, session, plan.origin.x, plan.origin.y, plan.origin.z);
                session.hasBeenPositioned = true;
            }

            player.sendSystemMessage(ChatUtil.vb("Planning complete: " + plan.steps.size() + " features to build."));
        });

        // ── STAGE 2: EXECUTOR ──
        Vibebuild.LOGGER.info("[VB] [EXECUTOR] Starting {} steps", plan.steps.size());
        int totalToolCount = 0;

        for (int i = 0; i < plan.steps.size(); i++) {
            checkCancelled(session);

            Plan.Step step = plan.steps.get(i);
            int stepNum = i + 1;
            int totalSteps = plan.steps.size();
            int stepIdx = i;

            runOnServerThread(() -> {
                ServerPlayer player = getPlayer(playerName);
                if (player == null) return;
                session.phase = BuildSession.Phase.BUILDING;
                player.sendSystemMessage(ChatUtil.vb("[" + stepNum + "/" + totalSteps + "] " + step.feature));
            });

            int stepTools = runExecutor(model, playerName, session, prompt, plan, step, stepNum, totalSteps, stepIdx);
            totalToolCount += stepTools;
            Vibebuild.LOGGER.info("[VB] [STEP {}/{}] {} — {} tools", stepNum, totalSteps, step.id, stepTools);
        }

        // ── STAGE 3: FINALIZER ──
        long finalizerStart = System.currentTimeMillis();
        Vibebuild.LOGGER.info("[VB] [FINALIZER] Generating summary...");
        String summary = runFinalizer(model, prompt, plan, totalToolCount);
        Vibebuild.LOGGER.info("[VB] [FINALIZER] Done in {}ms", System.currentTimeMillis() - finalizerStart);

        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        int finalToolCount = totalToolCount;

        runOnServerThreadAndWait(() -> {
            ServerPlayer player = getPlayer(playerName);
            if (player == null) return;

            if (!summary.isBlank()) {
                player.sendSystemMessage(ChatUtil.vbGray(summary));
            }

            boolean saved = Vibebuild.getInstance().getSchematicManager()
                    .saveBuildToClipboard(player, session);

            session.phase = BuildSession.Phase.REVIEWING;

            player.sendSystemMessage(ChatUtil.vb(String.format(
                    "Build complete! %d steps, %d commands in %ds.",
                    plan.steps.size(), finalToolCount, elapsed)));

            if (saved) {
                Vibebuild.getInstance().sendBuildBoundsToClient(player, session);
                player.sendSystemMessage(ChatUtil.vb("Fly around to review your build."));
                player.sendSystemMessage(ChatUtil.vb("Type /vb confirm to accept, /vb cancel to discard."));
            } else {
                player.sendSystemMessage(ChatUtil.vbError("Could not save schematic. Use /vb cancel to return."));
            }
        });

        Vibebuild.LOGGER.info("[VB] [DONE] {} commands across {} features in {}s",
                finalToolCount, plan.steps.size(), elapsed);
    }

    // ── Stage implementations ──

    private static Plan runPlanner(ChatModel model, BuildSession session, String prompt, BlockPos pos) {
        String plannerUserText = "Player position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n"
                + "Build request: " + prompt;

        session.plannerHistory.add(new BuildSession.HistoryMessage("user", plannerUserText));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(loadPrompt("spatial.txt")));
        messages.add(SystemMessage.from(loadPrompt("planner.txt")));
        for (BuildSession.HistoryMessage msg : session.plannerHistory) {
            if (msg.role.equals("user")) messages.add(UserMessage.from(msg.content));
            else messages.add(AiMessage.from(msg.content));
        }

        ToolSpecification submitPlan = ToolSpecs.submitPlan();

        Vibebuild.LOGGER.info("[VB] [PLANNER] Sending chat request ({} messages)...", messages.size());
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of(submitPlan))
                .build());
        Vibebuild.LOGGER.info("[VB] [PLANNER] Got response. hasToolCalls={}", response.aiMessage().hasToolExecutionRequests());

        AiMessage aiMessage = response.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            throw new RuntimeException("Planner did not call submit_plan. Response: " +
                    (aiMessage.text() != null ? aiMessage.text().substring(0, Math.min(200, aiMessage.text().length())) : "empty"));
        }

        ToolExecutionRequest toolCall = aiMessage.toolExecutionRequests().get(0);
        if (!toolCall.name().equals("submit_plan")) {
            throw new RuntimeException("Planner called unexpected tool: " + toolCall.name());
        }

        Plan plan = Plan.fromJson(toolCall.arguments());

        // Record in history so follow-up prompts have context
        String planSummary = "Plan: \"" + plan.planTitle + "\" at (" +
                plan.origin.x + ", " + plan.origin.y + ", " + plan.origin.z + ")\n" +
                plan.steps.stream()
                        .map(s -> "- " + s.id + ": " + s.feature + " — " + s.details.substring(0, Math.min(200, s.details.length())))
                        .collect(Collectors.joining("\n"));
        session.plannerHistory.add(new BuildSession.HistoryMessage("assistant", planSummary));

        return plan;
    }

    private static int runExecutor(ChatModel model, String playerName, BuildSession session,
                                   String prompt, Plan plan, Plan.Step step,
                                   int stepNum, int totalSteps, int stepIdx) {
        // Build context about recent steps
        String recentSteps;
        int start = Math.max(0, stepIdx - 2);
        if (start == stepIdx) {
            recentSteps = "none";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int j = start; j < stepIdx; j++) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(j + 1).append(". ").append(plan.steps.get(j).id)
                        .append(": ").append(plan.steps.get(j).feature);
            }
            recentSteps = sb.toString();
        }

        String userText = "Player request: " + prompt + "\n"
                + "Build origin: " + plan.origin.x + ", " + plan.origin.y + ", " + plan.origin.z + "\n"
                + "Recent completed steps (latest up to 2): " + recentSteps + "\n"
                + "\n"
                + "Current step (" + stepNum + "/" + totalSteps + "):\n"
                + "Feature: " + step.feature + "\n"
                + "Details: " + step.details;

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(loadPrompt("spatial.txt")));
        messages.add(SystemMessage.from(loadPrompt("executor.txt")));
        messages.add(UserMessage.from(userText));

        List<ToolSpecification> tools = ToolSpecs.worldEditTools();
        int toolCount = 0;

        int loopIter = 0;
        while (true) {
            checkCancelled(session);
            loopIter++;

            Vibebuild.LOGGER.info("[VB] [EXECUTOR] Step {}/{} — API call #{} ({} messages)...",
                    stepNum, totalSteps, loopIter, messages.size());
            long callStart = System.currentTimeMillis();
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build());
            Vibebuild.LOGGER.info("[VB] [EXECUTOR] API call #{} returned in {}ms, hasToolCalls={}",
                    loopIter, System.currentTimeMillis() - callStart, response.aiMessage().hasToolExecutionRequests());

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                Vibebuild.LOGGER.info("[VB] [EXECUTOR] Step {}/{} done — no more tool calls. AI text: {}",
                        stepNum, totalSteps,
                        aiMessage.text() != null ? aiMessage.text().substring(0, Math.min(100, aiMessage.text().length())) : "null");
                break;
            }

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                checkCancelled(session);
                toolCount++;

                Vibebuild.LOGGER.info("[VB]   [TOOL #{}] {} args={}", toolCount, request.name(),
                        request.arguments() != null ? request.arguments().substring(0, Math.min(200, request.arguments().length())) : "null");
                long toolStart = System.currentTimeMillis();
                String result = executeToolOnServerThread(playerName, session, request.name(), request.arguments());
                Vibebuild.LOGGER.info("[VB]   [TOOL #{}] {} completed in {}ms result={}",
                        toolCount, request.name(), System.currentTimeMillis() - toolStart,
                        result.substring(0, Math.min(200, result.length())));
                messages.add(ToolExecutionResultMessage.from(request, result));
            }
        }

        return toolCount;
    }

    private static String runFinalizer(ChatModel model, String prompt, Plan plan, int totalToolCount) {
        String userText = "Player request: " + prompt + "\n"
                + "Completed: " + plan.steps.size() + " features, " + totalToolCount + " total commands\n"
                + "Features built: " + plan.steps.stream().map(s -> s.feature).collect(Collectors.joining(", "));

        ChatModel finalizerModel = AnthropicChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder())
                .apiKey(VibeBuildConfig.getApiKey())
                .modelName(VibeBuildConfig.getModel())
                .maxTokens(1024)
                .timeout(Duration.ofHours(1))
                .build();

        Vibebuild.LOGGER.info("[VB] [FINALIZER] Sending chat request...");
        ChatResponse response = finalizerModel.chat(ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(loadPrompt("finalizer.txt")),
                        UserMessage.from(userText)))
                .build());
        Vibebuild.LOGGER.info("[VB] [FINALIZER] Got response");

        return response.aiMessage().text() != null ? response.aiMessage().text().trim() : "";
    }

    // ── Tool execution ──

    private static String executeToolOnServerThread(String playerName, BuildSession session,
                                                    String toolName, String argsJson) {
        CompletableFuture<String> future = new CompletableFuture<>();
        MinecraftServer server = Vibebuild.getInstance().getServer();

        server.execute(() -> {
            try {
                ServerPlayer player = getPlayer(playerName);
                if (player == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("success", false);
                    err.addProperty("message", "Player disconnected");
                    future.complete(GSON.toJson(err));
                    return;
                }

                JsonObject args = GSON.fromJson(argsJson, JsonObject.class);
                JsonObject result = Vibebuild.getInstance().getToolExecutor()
                        .execute(player, session, toolName, args);
                Vibebuild.getInstance().getToolExecutor().updateBounds(session, toolName, args);

                boolean success = result.has("success") && result.get("success").getAsBoolean();
                if (!success) {
                    String errMsg = result.has("message") ? result.get("message").getAsString() : "unknown";
                    player.sendSystemMessage(ChatUtil.vbError("Tool " + toolName + " failed: " + errMsg));
                }

                future.complete(GSON.toJson(result));
            } catch (Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty("success", false);
                err.addProperty("message", e.getMessage());
                future.complete(GSON.toJson(err));
            }
        });

        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"Tool execution timed out\"}";
        }
    }

    // ── Helpers ──

    private static void checkCancelled(BuildSession session) {
        if (session.cancelled) {
            throw new RuntimeException("Build cancelled by player");
        }
    }

    private static ServerPlayer getPlayer(String name) {
        return Vibebuild.getInstance().getServer().getPlayerList().getPlayerByName(name);
    }

    private static void runOnServerThread(Runnable r) {
        Vibebuild.getInstance().getServer().execute(r);
    }

    private static void runOnServerThreadAndWait(Runnable r) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Vibebuild.getInstance().getServer().execute(() -> {
            try {
                r.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Server thread operation failed", e);
        }
    }

    private static String loadPrompt(String filename) {
        try (InputStream is = BuildPipeline.class.getClassLoader()
                .getResourceAsStream("prompts/" + filename)) {
            if (is == null) throw new RuntimeException("Prompt not found: " + filename);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt: " + filename, e);
        }
    }
}
