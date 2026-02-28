package com.vibebuild.ai.pipeline;

/**
 * Immutable string/config bundle for a pipeline domain.
 *
 * This groups constant labels and prompt paths so domain profiles do not
 * need to implement one-method-per-string overrides.
 *
 * Fields ending in {@code Template} are {@link String#format} patterns:
 *   - {@code planningCompleteTemplate}:  args (int totalSteps)
 *   - {@code completionTemplate}:        args (int totalSteps, int totalToolCount, long elapsedSeconds)
 *   - {@code doneLogTemplate}:           args (int totalToolCount, int totalSteps, long elapsedSeconds)
 *
 * Image-related fields ({@code plannerImageDerivedPromptPath}, {@code imagePromptPath},
 * {@code imageAnalysisMessage}) should be {@code null} for profiles that do not
 * support image input (e.g. the redstone profile).
 */
public record PipelineStrings(
        String logPrefix,
        String planningStartMessage,
        String planningCompleteTemplate,
        String completionTemplate,
        String reviewMessage,
        String reviewCommandHintMessage,
        String doneLogTemplate,
        String cancelledExceptionMessage,
        String requestLabel,
        String originLabel,
        String completedStepsLabel,
        String currentStepLabel,
        String finalizerUnitsLabel,
        String finalizerBuiltLabel,
        String planHistoryLabel,
        String spatialPromptPath,
        String plannerPromptPath,
        String executorPromptPath,
        String finalizerPromptPath,
        String plannerImageDerivedPromptPath,
        String imagePromptPath,
        String imageAnalysisMessage
) {

    /** Format planning completion line with step count. */
    public String planningCompleteMessage(int totalSteps) {
        return String.format(planningCompleteTemplate, totalSteps);
    }

    /** Format completion line with totals and elapsed time. */
    public String completionMessage(int totalSteps, int totalToolCount, long elapsedSeconds) {
        return String.format(completionTemplate, totalSteps, totalToolCount, elapsedSeconds);
    }

    /** Format final done log summary. */
    public String doneLogMessage(int totalToolCount, int totalSteps, long elapsedSeconds) {
        return String.format(doneLogTemplate, totalToolCount, totalSteps, elapsedSeconds);
    }
}
