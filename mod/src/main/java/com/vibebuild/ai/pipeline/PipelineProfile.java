package com.vibebuild.ai.pipeline;

import com.vibebuild.ai.Plan;
import com.vibebuild.session.BuildSession;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * Configuration contract for one pipeline domain (build, redstone, etc.).
 *
 * This keeps the shared runner focused on orchestration while domain
 * specifics remain pluggable. Human-facing text and prompt paths live in
 * {@link PipelineStrings}; behavior hooks (tools, memory, execution bridge)
 * stay on this interface.
 */
public interface PipelineProfile {

    /** Immutable text + prompt-path configuration for this domain. */
    PipelineStrings strings();

    /**
     * Whether planner may fall back to parsing raw JSON text when tool calls
     * are absent.
     */
    default boolean allowPlannerTextFallback() {
        return false;
    }

    /** Max output tokens for model calls in this profile. */
    default int maxTokens() {
        return 32768;
    }

    /** Accessor for planner memory list in session state. */
    List<BuildSession.HistoryMessage> plannerHistory(BuildSession session);

    /** Planner tool contract expected from the planner stage. */
    ToolSpecification plannerTool();

    /** Executor tool contracts available during per-step execution. */
    List<ToolSpecification> executorTools();

    /** Domain-specific tool executor bridge. */
    PipelineToolBridge toolBridge();

    /**
     * Build the planner-history summary entry from a parsed plan.
     *
     * This summary is appended to history as an assistant message so later
     * reprompts can refine existing work.
     */
    default String formatPlanHistorySummary(Plan plan) {
        PipelineStrings strings = strings();
        StringBuilder sb = new StringBuilder();
        sb.append(strings.planHistoryLabel())
                .append(": \"")
                .append(plan.planTitle)
                .append("\" at (")
                .append(plan.origin.x)
                .append(", ")
                .append(plan.origin.y)
                .append(", ")
                .append(plan.origin.z)
                .append(")");

        for (Plan.Step step : plan.steps) {
            sb.append("\n- ")
                    .append(step.id)
                    .append(": ")
                    .append(step.feature)
                    .append(" — ")
                    .append(step.details);
        }
        return sb.toString();
    }
}
