package com.vibebuild.redstone.ai;

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
 * Redstone-domain profile for the shared pipeline runner.
 */
public class RedstonePipelineProfile implements PipelineProfile {

    private static final PipelineStrings STRINGS = new PipelineStrings(
            "[VB-RS]",
            "Planning your redstone circuit...",
            "Circuit planned: %d subsystems to build.",
            "Circuit complete! %d subsystems, %d commands in %ds.",
            "Fly around to review your circuit.",
            "Type /vb confirm to accept, /vb cancel to discard.",
            "%d commands across %d subsystems in %ds",
            "Circuit build cancelled by player",
            "Circuit request",
            "Circuit origin",
            "All completed subsystems",
            "Current subsystem",
            "subsystems",
            "Subsystems built",
            "Circuit plan",
            "redstone/spatial.txt",
            "redstone/planner.txt",
            "redstone/executor.txt",
            "redstone/finalizer.txt",
            null,  // plannerImageDerivedPromptPath — image input not supported for redstone
            null,  // imagePromptPath
            null   // imageAnalysisMessage
    );

    private static final ToolSpecification PLANNER_TOOL = RedstoneToolSpecs.submitPlan();
    private static final List<ToolSpecification> EXECUTOR_TOOLS = RedstoneToolSpecs.redstoneTools();

    private static final PipelineToolBridge TOOL_BRIDGE = new PipelineToolBridge() {
        @Override
        public JsonObject execute(ServerPlayer player, BuildSession session, String toolName, JsonObject args) {
            return Vibebuild.getInstance().getRedstoneToolExecutor().execute(player, session, toolName, args);
        }

        @Override
        public void updateBounds(BuildSession session, String toolName, JsonObject args) {
            Vibebuild.getInstance().getRedstoneToolExecutor().updateBounds(session, toolName, args);
        }
    };

    @Override
    public PipelineStrings strings() {
        return STRINGS;
    }

    @Override
    public List<BuildSession.HistoryMessage> plannerHistory(BuildSession session) {
        return session.redstonePlannerHistory;
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
