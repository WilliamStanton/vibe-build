package com.vibebuild.ai.pipeline;

import com.google.gson.JsonObject;
import com.vibebuild.session.BuildSession;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bridges shared pipeline orchestration to domain-specific tool executors.
 *
 * The shared runner is provider/prompt agnostic, but tool execution remains
 * domain-specific (normal build tools vs redstone tools). Implementations map
 * calls to the corresponding executor and bounds updater.
 */
public interface PipelineToolBridge {

    /** Execute one tool call and return a standard JSON payload. */
    JsonObject execute(ServerPlayer player, BuildSession session, String toolName, JsonObject args);

    /** Update session bounds for successful mutating tool calls. */
    void updateBounds(BuildSession session, String toolName, JsonObject args);
}
