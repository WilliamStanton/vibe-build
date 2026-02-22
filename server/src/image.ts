import { chat } from "@tanstack/ai";
import imageToBuildPrompt from "../prompts/image-to-build.system.txt";
import { imageAdapter } from "./config";

const collectTextOutput = async (
	stream: AsyncIterable<{ type: string; [key: string]: unknown }>,
) => {
	let text = "";
	let runError: string | null = null;
	let finishReason: string | null = null;

	for await (const chunk of stream) {
		if (chunk.type === "TEXT_MESSAGE_CONTENT") {
			const delta = chunk.delta;
			if (typeof delta === "string") {
				text += delta;
			}
		}

		if (chunk.type === "RUN_ERROR") {
			const error = chunk.error as { message?: string } | undefined;
			runError = error?.message ?? "Unknown model error";
		}

		if (chunk.type === "RUN_FINISHED") {
			const reason = chunk.finishReason;
			finishReason = typeof reason === "string" ? reason : null;
		}
	}

	if (runError) {
		throw new Error(`Image model failed: ${runError}`);
	}

	return {
		text: text.trim(),
		finishReason,
	};
};

export const generateBuildPromptFromImage = async (
	image: File,
	notes: string,
) => {
	const arr = await image.arrayBuffer();
	const b64 = Buffer.from(arr).toString("base64");
	const mimeType = (image.type || "image/png").toLowerCase();

	const mediaType =
		mimeType === "image/jpg"
			? "image/jpeg"
			: mimeType === "image/png" ||
					mimeType === "image/jpeg" ||
					mimeType === "image/gif" ||
					mimeType === "image/webp"
				? mimeType
				: "image/png";

	const userText = [
		"Analyze this Minecraft build reference image and convert it into a planner-ready build request.",
		notes ? `Player notes: ${notes}` : "Player notes: none",
	].join("\n");

	const stream = chat({
		adapter: imageAdapter,
		maxTokens: 2200,
		systemPrompts: [imageToBuildPrompt],
		messages: [
			{
				role: "user",
				content: [
					{ type: "text", content: userText },
					{
						type: "image",
						source: {
							type: "data",
							value: b64,
							mimeType: mediaType,
						},
					},
				],
			},
		],
	});

	const result = await collectTextOutput(stream);
	const generated = result.text;

	if (!generated) {
		throw new Error(
			`Image model returned an empty response (finish_reason=${result.finishReason ?? "unknown"}).`,
		);
	}

	console.log("[IMAGE PROMPT] Generated prompt from image:\n", generated);
	return generated;
};
