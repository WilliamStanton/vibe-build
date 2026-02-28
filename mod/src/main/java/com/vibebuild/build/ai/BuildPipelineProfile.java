package com.vibebuild.build.ai;

import com.google.gson.JsonObject;
import com.vibebuild.Vibebuild;
import com.vibebuild.ai.pipeline.PipelineProfile;
import com.vibebuild.ai.pipeline.PipelineStrings;
import com.vibebuild.ai.pipeline.PipelineToolBridge;
import com.vibebuild.session.BuildSession;
import dev.langchain4j.agent.tool.ToolSpecification;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Build-domain profile for the shared pipeline runner.
 */
public class BuildPipelineProfile implements PipelineProfile {

    private static final PipelineStrings STRINGS = new PipelineStrings(
            "[VB]",
            "Planning your build...",
            "Planning complete: %d features to build.",
            "Build complete! %d steps, %d commands in %ds.",
            "Fly around to review your build.",
            "Type /vb confirm to accept, /vb cancel to discard.",
            "%d commands across %d features in %ds",
            "Build cancelled by player",
            "Build request",
            "Build origin",
            "All completed steps",
            "Current step",
            "features",
            "Features built",
            "Plan",
            "build/spatial.txt",
            "build/planner.txt",
            "build/executor.txt",
            "build/finalizer.txt",
            "build/planner-image-derived.txt",
            "build/image.txt",
            "Analyzing reference image..."
    );

    private static final ToolSpecification PLANNER_TOOL = BuildToolSpecs.submitPlan();
    private static final List<ToolSpecification> EXECUTOR_TOOLS = BuildToolSpecs.worldEditTools();

    private static final PipelineToolBridge TOOL_BRIDGE = new PipelineToolBridge() {
        @Override
        public JsonObject execute(ServerPlayer player, BuildSession session, String toolName, JsonObject args) {
            return Vibebuild.getInstance().getToolExecutor().execute(player, session, toolName, args);
        }

        @Override
        public void updateBounds(BuildSession session, String toolName, JsonObject args) {
            Vibebuild.getInstance().getToolExecutor().updateBounds(session, toolName, args);
        }
    };

    @Override
    public PipelineStrings strings() {
        return STRINGS;
    }

    @Override
    public boolean allowPlannerTextFallback() {
        return true;
    }

    @Override
    public List<BuildSession.HistoryMessage> plannerHistory(BuildSession session) {
        return session.plannerHistory;
    }

    @Override
    public ToolSpecification plannerTool() {
        return PLANNER_TOOL;
    }

    @Override
    public List<ToolSpecification> executorTools() {
        return EXECUTOR_TOOLS;
    }

    @Override
    public PipelineToolBridge toolBridge() {
        return TOOL_BRIDGE;
    }
}
