package com.vibebuild.redstone.ai;

import com.vibebuild.ai.pipeline.PipelineProfile;
import com.vibebuild.ai.pipeline.SharedPipelineRunner;
import com.vibebuild.session.BuildSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Entry point for the redstone pipeline.
 *
 * Execution flow is orchestrated by {@link SharedPipelineRunner}; this
 * class supplies the redstone-specific profile and public API shape.
 */
public class RedstonePipeline {

    private static final PipelineProfile PROFILE = new RedstonePipelineProfile();

    /**
     * Start the redstone pipeline asynchronously for the given prompt.
     */
    public static void runAsync(ServerPlayer player, BuildSession session,
                                String prompt, BlockPos playerPos) {
        SharedPipelineRunner.runAsync(player, session, prompt, playerPos, PROFILE);
    }
}
