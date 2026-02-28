package com.vibebuild.ai.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vibebuild.ChatUtil;
import com.vibebuild.Vibebuild;
import com.vibebuild.ai.ChatModelFactory;
import com.vibebuild.ai.Plan;
import com.vibebuild.ai.PromptLoader;
import com.vibebuild.config.VibeBuildConfig;
import com.vibebuild.session.BuildSession;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shared orchestration engine for all VibeBuild AI pipelines.
 *
 * This class centralizes common flow mechanics (planner -> executor ->
 * finalizer, async lifecycle, server-thread tool dispatch, session phase
 * transitions) while delegating domain-specific behavior through
 * {@link PipelineProfile}.
 */
public final class SharedPipelineRunner {

    private static final Gson GSON = new Gson();

    private SharedPipelineRunner() {}

    /**
     * Starts one pipeline run asynchronously.
     *
     * This is the main entrypoint used by both build and redstone wrappers.
     * The method performs lightweight guards and immediately returns while all
     * heavy work runs on a background thread.
     */
    public static void runAsync(ServerPlayer player, BuildSession session, String prompt,
                                BlockPos playerPos, PipelineProfile profile) {
        if (session.processingPrompt) {
            player.sendSystemMessage(ChatUtil.vbError("Build already in progress. Wait or /vb cancel."));
            return;
        }

        session.processingPrompt = true;
        session.cancelled = false;

        final String playerName = player.getName().getString();

        CompletableFuture.runAsync(() -> {
            try {
                run(playerName, session, prompt, playerPos, profile);
            } catch (Throwable e) {
                Vibebuild.LOGGER.error("{} Pipeline error: {} - {}",
                        profile.strings().logPrefix(), e.getClass().getName(), e.getMessage(), e);
                runOnServerThread(() -> {
                    ServerPlayer p = getPlayer(playerName);
                    if (p != null) {
                        String errMsg = e.getClass().getSimpleName()
                                + (e.getMessage() != null ? ": " + e.getMessage() : "");
                        p.sendSystemMessage(ChatUtil.vbError(errMsg));
                    }

                    if (session.inVibeWorldSession) {
                        session.phase = BuildSession.Phase.REVIEWING;
                        if (p != null) {
                            p.sendSystemMessage(ChatUtil.vb("You can reprompt or /vb cancel to return."));
                        }
                    } else {
                        session.phase = BuildSession.Phase.CONNECTED;
                    }
                });
            } finally {
                session.processingPrompt = false;
            }
        });
    }

    /**
     * Runs the full pipeline lifecycle synchronously on a worker thread.
     *
     * Lifecycle: model setup -> teleport/init -> planner -> per-step executor
     * loop -> finalizer -> schematic copy/review messaging.
     */
    private static void run(String playerName, BuildSession session, String prompt,
                            BlockPos playerPos, PipelineProfile profile) {
        PipelineStrings strings = profile.strings();
        String apiKey = VibeBuildConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("No API key set. Use /vb apikey <key> to set one.");
        }

        VibeBuildConfig.Provider provider = VibeBuildConfig.getProvider();
        Vibebuild.LOGGER.info("{} Building ChatModel with model='{}' provider={} apiKey={}...",
                strings.logPrefix(),
                VibeBuildConfig.getModel(),
                provider,
                maskApiKey(apiKey));

        ChatModel model;
        try {
            model = ChatModelFactory.createFromConfig(profile.maxTokens());
            Vibebuild.LOGGER.info("{} ChatModel built successfully", strings.logPrefix());
        } catch (Exception e) {
            Vibebuild.LOGGER.error("{} Failed to build ChatModel: {} - {}",
                    strings.logPrefix(), e.getClass().getName(), e.getMessage(), e);
            throw e;
        }

        // Teleport to build dimension and set planning phase.
        runOnServerThreadAndWait(() -> {
            ServerPlayer player = getPlayer(playerName);
            if (player == null) throw new RuntimeException("Player disconnected");

            session.phase = BuildSession.Phase.PLANNING;
            player.sendSystemMessage(ChatUtil.vb(strings.planningStartMessage()));

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

        String requestForPlanner = prompt;
        boolean imageMode = strings.imagePromptPath() != null && session.imageBase64 != null;
        boolean includeRawImageInput = imageMode;

        // Optional image -> request stage.
        if (imageMode) {
            String imageMsg = strings.imageAnalysisMessage();
            if (imageMsg != null && !imageMsg.isBlank()) {
                runOnServerThread(() -> {
                    ServerPlayer player = getPlayer(playerName);
                    if (player != null) {
                        player.sendSystemMessage(ChatUtil.vb(imageMsg));
                    }
                });
            }

            long imagePromptStart = System.currentTimeMillis();
            try {
                String generatedRequest = runImageToRequestPrompt(
                        model,
                        prompt,
                        session.imageBase64,
                        session.imageMimeType,
                        strings.imagePromptPath()
                );

                if (!generatedRequest.isBlank()) {
                    requestForPlanner = generatedRequest;
                    includeRawImageInput = false;
                    Vibebuild.LOGGER.info("{} [IMAGE] Converted image + notes to request in {}ms",
                            strings.logPrefix(), System.currentTimeMillis() - imagePromptStart);
                }
            } catch (Exception e) {
                Vibebuild.LOGGER.warn("{} [IMAGE] Prompt synthesis failed, falling back to multimodal planner input: {}",
                        strings.logPrefix(), e.getMessage());
            }
        }

        // Stage 1: planner.
        Vibebuild.LOGGER.info("{} [PLANNER] Starting for '{}' with model '{}'",
                strings.logPrefix(), requestForPlanner, VibeBuildConfig.getModel());
        Plan plan = runPlanner(model, session, requestForPlanner, playerPos,
                includeRawImageInput, imageMode, profile);
        Vibebuild.LOGGER.info("{} [PLANNER] Done in {}ms — Plan: '{}' with {} steps",
                strings.logPrefix(), System.currentTimeMillis() - t0, plan.planTitle, plan.steps.size());

        checkCancelled(session, profile);

        runOnServerThreadAndWait(() -> {
            ServerPlayer player = getPlayer(playerName);
            if (player == null) return;

            if (!session.hasBeenPositioned) {
                session.buildOrigin = new BlockPos(plan.origin.x, plan.origin.y, plan.origin.z);
                Vibebuild.getInstance().getBuildDimension().repositionToFaceBuild(
                        player, session, plan.origin.x, plan.origin.y, plan.origin.z);
                session.hasBeenPositioned = true;
            }

            player.sendSystemMessage(ChatUtil.vb(strings.planningCompleteMessage(plan.steps.size())));
        });

        // Stage 2: executor.
        Vibebuild.LOGGER.info("{} [EXECUTOR] Starting {} steps", strings.logPrefix(), plan.steps.size());
        int totalToolCount = 0;

        for (int i = 0; i < plan.steps.size(); i++) {
            checkCancelled(session, profile);

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

            int stepTools = runExecutor(model, playerName, session, requestForPlanner,
                    plan, step, stepNum, totalSteps, stepIdx, profile);
            totalToolCount += stepTools;
            Vibebuild.LOGGER.info("{} [STEP {}/{}] {} — {} tools",
                    strings.logPrefix(), stepNum, totalSteps, step.id, stepTools);
        }

        // Stage 3: finalizer.
        long finalizerStart = System.currentTimeMillis();
        Vibebuild.LOGGER.info("{} [FINALIZER] Generating summary...", strings.logPrefix());
        String summary = runFinalizer(model, requestForPlanner, plan, totalToolCount, profile);
        Vibebuild.LOGGER.info("{} [FINALIZER] Done in {}ms",
                strings.logPrefix(), System.currentTimeMillis() - finalizerStart);

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
            player.sendSystemMessage(ChatUtil.vb(
                    strings.completionMessage(plan.steps.size(), finalToolCount, elapsed)
            ));

            if (saved) {
                Vibebuild.getInstance().sendBuildBoundsToClient(player, session);
                player.sendSystemMessage(ChatUtil.vb(strings.reviewMessage()));
                player.sendSystemMessage(ChatUtil.vb(strings.reviewCommandHintMessage()));
            } else {
                player.sendSystemMessage(ChatUtil.vbError("Could not save schematic. Use /vb cancel to return."));
            }
        });

        Vibebuild.LOGGER.info("{} [DONE] {}",
                strings.logPrefix(),
                strings.doneLogMessage(finalToolCount, plan.steps.size(), elapsed));
    }

    // -- Stage implementations --

    /**
     * Planner stage that converts user intent into a structured {@link Plan}.
     */
    private static Plan runPlanner(ChatModel model, BuildSession session, String prompt,
                                   BlockPos pos, boolean includeRawImageInput,
                                   boolean imageMode, PipelineProfile profile) {
        PipelineStrings strings = profile.strings();
        String plannerUserText = "Player position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n"
                + strings.requestLabel() + ": " + prompt;

        List<BuildSession.HistoryMessage> history = profile.plannerHistory(session);
        history.add(new BuildSession.HistoryMessage("user", plannerUserText));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(PromptLoader.load(strings.spatialPromptPath())));
        messages.add(SystemMessage.from(PromptLoader.load(strings.plannerPromptPath())));

        String plannerImagePromptPath = strings.plannerImageDerivedPromptPath();
        if (imageMode && plannerImagePromptPath != null && !plannerImagePromptPath.isBlank()) {
            messages.add(SystemMessage.from(PromptLoader.load(plannerImagePromptPath)));
        }

        for (int i = 0; i < history.size(); i++) {
            BuildSession.HistoryMessage msg = history.get(i);
            boolean isLastUserMsg = "user".equals(msg.role) && i == history.size() - 1;

            if ("user".equals(msg.role)) {
                if (includeRawImageInput && isLastUserMsg && session.imageBase64 != null) {
                    messages.add(UserMessage.from(
                            TextContent.from(msg.content),
                            ImageContent.from(session.imageBase64, session.imageMimeType)
                    ));
                } else {
                    messages.add(UserMessage.from(msg.content));
                }
            } else {
                messages.add(AiMessage.from(msg.content));
            }
        }

        ToolSpecification plannerTool = profile.plannerTool();

        Vibebuild.LOGGER.info("{} [PLANNER] Sending chat request ({} messages)...",
                strings.logPrefix(), messages.size());
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of(plannerTool))
                .build());
        Vibebuild.LOGGER.info("{} [PLANNER] Got response. hasToolCalls={}",
                strings.logPrefix(), response.aiMessage().hasToolExecutionRequests());

        String planJson = extractPlanJson(response.aiMessage(), plannerTool, profile);
        Plan plan = Plan.fromJson(planJson);

        history.add(new BuildSession.HistoryMessage("assistant", profile.formatPlanHistorySummary(plan)));
        return plan;
    }

    /**
     * Executor stage for a single plan step.
     *
     * Runs an iterative tool-calling loop until the model stops requesting
     * tools. Tool calls are executed on the Minecraft server thread.
     */
    private static int runExecutor(ChatModel model, String playerName, BuildSession session,
                                   String prompt, Plan plan, Plan.Step step,
                                   int stepNum, int totalSteps, int stepIdx,
                                   PipelineProfile profile) {
        PipelineStrings strings = profile.strings();
        String recentSteps = buildRecentSteps(plan, stepIdx);

        String userText = strings.requestLabel() + ": " + prompt + "\n"
                + strings.originLabel() + ": " + plan.origin.x + ", " + plan.origin.y + ", " + plan.origin.z + "\n"
                + strings.completedStepsLabel() + ": " + recentSteps + "\n"
                + "\n"
                + strings.currentStepLabel() + " (" + stepNum + "/" + totalSteps + "):\n"
                + "Feature: " + step.feature + "\n"
                + "Details: " + step.details;

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(PromptLoader.load(strings.spatialPromptPath())));
        messages.add(SystemMessage.from(PromptLoader.load(strings.executorPromptPath())));

        UserMessage executorUserMessage = UserMessage.from(userText);
        executorUserMessage.attributes().put("cache_control", "ephemeral");
        messages.add(executorUserMessage);

        List<ToolSpecification> tools = profile.executorTools();
        int toolCount = 0;

        int loopIter = 0;
        while (true) {
            checkCancelled(session, profile);
            loopIter++;

            Vibebuild.LOGGER.info("{} [EXECUTOR] Step {}/{} — API call #{} ({} messages)...",
                    strings.logPrefix(), stepNum, totalSteps, loopIter, messages.size());

            long callStart = System.currentTimeMillis();
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build());
            Vibebuild.LOGGER.info("{} [EXECUTOR] API call #{} returned in {}ms, hasToolCalls={}",
                    strings.logPrefix(),
                    loopIter,
                    System.currentTimeMillis() - callStart,
                    response.aiMessage().hasToolExecutionRequests());

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                Vibebuild.LOGGER.info("{} [EXECUTOR] Step {}/{} done — no more tool calls. AI text: {}",
                        strings.logPrefix(), stepNum, totalSteps, preview(aiMessage.text(), 100));
                break;
            }

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                checkCancelled(session, profile);
                toolCount++;

                Vibebuild.LOGGER.info("{}   [TOOL #{}] {} args={}",
                        strings.logPrefix(),
                        toolCount,
                        request.name(),
                        preview(request.arguments(), 200));

                long toolStart = System.currentTimeMillis();
                String result = executeToolOnServerThread(playerName, session, request.name(), request.arguments(), profile);
                Vibebuild.LOGGER.info("{}   [TOOL #{}] {} completed in {}ms result={}",
                        strings.logPrefix(),
                        toolCount,
                        request.name(),
                        System.currentTimeMillis() - toolStart,
                        preview(result, 200));

                messages.add(ToolExecutionResultMessage.from(request, result));
            }
        }

        return toolCount;
    }

    /**
     * Finalizer stage that generates a player-facing summary text.
     */
    private static String runFinalizer(ChatModel model, String prompt, Plan plan,
                                       int totalToolCount, PipelineProfile profile) {
        PipelineStrings strings = profile.strings();
        String userText = strings.requestLabel() + ": " + prompt + "\n"
                + "Completed: " + plan.steps.size() + " " + strings.finalizerUnitsLabel()
                + ", " + totalToolCount + " total commands\n"
                + strings.finalizerBuiltLabel() + ": "
                + plan.steps.stream().map(s -> s.feature).collect(Collectors.joining(", "));

        Vibebuild.LOGGER.info("{} [FINALIZER] Sending chat request...", strings.logPrefix());
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(PromptLoader.load(strings.finalizerPromptPath())),
                        UserMessage.from(userText)
                ))
                .build());
        Vibebuild.LOGGER.info("{} [FINALIZER] Got response", strings.logPrefix());

        return response.aiMessage().text() != null ? response.aiMessage().text().trim() : "";
    }

    /**
     * Optional image-to-text request synthesis used by image-enabled profiles.
     */
    private static String runImageToRequestPrompt(ChatModel model, String playerNotes,
                                                  String imageBase64, String imageMimeType,
                                                  String imagePromptPath) {
        String notes = (playerNotes == null || playerNotes.isBlank()) ? "none" : playerNotes;
        String userText = "Player notes: " + notes + "\n"
                + "Generate one concrete Minecraft build request based on this reference image.";

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(PromptLoader.load(imagePromptPath)),
                        UserMessage.from(
                                TextContent.from(userText),
                                ImageContent.from(imageBase64, imageMimeType)
                        )
                ))
                .build());

        String rewritten = response.aiMessage().text() != null ? response.aiMessage().text().trim() : "";
        if (rewritten.isBlank()) {
            throw new RuntimeException("Image prompt stage returned empty text");
        }
        return rewritten;
    }

    // -- Tool execution --

    /**
     * Executes one tool call on the server thread and returns a JSON result.
     *
     * The model interaction runs off-thread, but world mutation must happen
     * on the MC server thread. This method bridges that boundary with timeout
     * protection.
     */
    private static String executeToolOnServerThread(String playerName, BuildSession session,
                                                    String toolName, String argsJson,
                                                    PipelineProfile profile) {
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

                JsonObject args;
                if (argsJson == null || argsJson.isBlank()) {
                    args = new JsonObject();
                } else {
                    args = GSON.fromJson(argsJson, JsonObject.class);
                    if (args == null) args = new JsonObject();
                }

                PipelineToolBridge toolBridge = profile.toolBridge();
                JsonObject result = toolBridge.execute(player, session, toolName, args);
                boolean success = result.has("success") && result.get("success").getAsBoolean();
                if (success) {
                    toolBridge.updateBounds(session, toolName, args);
                } else {
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

    // -- Helpers --

    /**
     * Extract the plan JSON either from planner tool call arguments or, when
     * enabled, from raw response text fallback.
     */
    private static String extractPlanJson(AiMessage aiMessage, ToolSpecification plannerTool,
                                          PipelineProfile profile) {
        if (aiMessage.hasToolExecutionRequests()) {
            ToolExecutionRequest toolCall = aiMessage.toolExecutionRequests().get(0);
            if (!toolCall.name().equals(plannerTool.name())) {
                throw new RuntimeException("Planner called unexpected tool: " + toolCall.name());
            }
            return toolCall.arguments();
        }

        if (profile.allowPlannerTextFallback()
                && aiMessage.text() != null
                && aiMessage.text().contains("\"planTitle\"")) {
            Vibebuild.LOGGER.warn("{} [PLANNER] Model returned plan text instead of tool call; parsing directly",
                    profile.strings().logPrefix());
            return stripCodeFence(aiMessage.text().trim());
        }

        throw new RuntimeException("Planner did not return a plan. Response: " + preview(aiMessage.text(), 200));
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }

        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline != -1 && lastFence > firstNewline) {
            return text.substring(firstNewline + 1, lastFence).trim();
        }
        return text;
    }

    /** Builds compact context of completed steps for executor prompts. */
    private static String buildRecentSteps(Plan plan, int currentStepIndex) {
        if (currentStepIndex == 0) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < currentStepIndex; i++) {
            if (sb.length() > 0) sb.append(" | ");
            Plan.Step previous = plan.steps.get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(previous.id)
                    .append(": ")
                    .append(previous.feature);
        }
        return sb.toString();
    }

    /** Throws when the session has been cancelled by the user. */
    private static void checkCancelled(BuildSession session, PipelineProfile profile) {
        if (session.cancelled) {
            throw new RuntimeException(profile.strings().cancelledExceptionMessage());
        }
    }

    private static String maskApiKey(String apiKey) {
        int visible = Math.min(8, apiKey.length());
        return apiKey.substring(0, visible) + "...";
    }

    private static String preview(String text, int maxChars) {
        if (text == null) {
            return "null";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
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
}
