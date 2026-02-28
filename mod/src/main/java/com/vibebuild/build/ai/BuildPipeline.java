package com.vibebuild.build.ai;

import com.vibebuild.ai.pipeline.PipelineProfile;
import com.vibebuild.ai.pipeline.SharedPipelineRunner;
import com.vibebuild.session.BuildSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.Base64;

/**
 * Entry point for the normal build pipeline.
 *
 * The heavy orchestration logic is now shared in
 * {@link SharedPipelineRunner}. This class keeps only build-specific entry
 * concerns (notably image prompt handling).
 */
public class BuildPipeline {

    private static final PipelineProfile PROFILE = new BuildPipelineProfile();

    /**
     * Start the build pipeline asynchronously for a text prompt.
     */
    public static void runAsync(ServerPlayer player, BuildSession session,
                                String prompt, BlockPos playerPos) {
        session.imageBase64 = null;
        session.imageMimeType = null;
        SharedPipelineRunner.runAsync(player, session, prompt, playerPos, PROFILE);
    }

    /**
     * Start the build pipeline asynchronously for image + text prompt.
     */
    public static void runAsync(ServerPlayer player, BuildSession session, String prompt,
                                byte[] imageBytes, String mimeType, BlockPos playerPos) {
        session.imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
        session.imageMimeType = mimeType;
        SharedPipelineRunner.runAsync(player, session, prompt, playerPos, PROFILE);
    }
}
