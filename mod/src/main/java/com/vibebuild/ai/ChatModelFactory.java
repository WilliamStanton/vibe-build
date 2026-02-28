package com.vibebuild.ai;

import com.vibebuild.config.VibeBuildConfig;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * Centralized provider-aware model construction for all agent pipelines.
 *
 * Keeping this logic in one place avoids drift between build and redstone
 * when provider settings or request defaults change.
 */
public final class ChatModelFactory {

    private ChatModelFactory() {}

    /**
     * Create a chat model for the selected provider using shared defaults.
     *
     * <p>OpenAI and Anthropic use different token parameter names, so this
     * method maps the shared {@code maxTokens} argument to the correct builder
     * option for each provider.</p>
     */
    public static ChatModel create(VibeBuildConfig.Provider provider, String apiKey,
                                   String modelName, int maxTokens) {
        if (provider == VibeBuildConfig.Provider.OPENAI) {
            return OpenAiChatModel.builder()
                    .httpClientBuilder(new JdkHttpClientBuilder())
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .maxCompletionTokens(maxTokens)
                    .timeout(Duration.ofHours(1))
                    .build();
        }

        return AnthropicChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder())
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .thinkingType("enabled")
                .timeout(Duration.ofHours(1))
                .beta("prompt-caching-2024-07-31")
                .cacheSystemMessages(true)
                .cacheTools(true)
                .build();
    }
}
