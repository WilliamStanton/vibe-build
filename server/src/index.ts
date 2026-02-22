import { type AnyTextAdapter, chat, toolDefinition } from "@tanstack/ai";
import { anthropicText } from "@tanstack/ai-anthropic";
import { openaiText } from "@tanstack/ai-openai";
import { type WebSocket, WebSocketServer } from "ws";
import { z } from "zod";
import executorPrompt from "../prompts/executor.system.txt";
import finalizerPrompt from "../prompts/finalizer.system.txt";
import imageToBuildPrompt from "../prompts/image-to-build.system.txt";
import plannerPrompt from "../prompts/planner.system.txt";
import spatialprefixPrompt from "../prompts/spatialprefix.system.txt";
import { allWorldEditTools as allTools } from "./tools";

const wsPort = Number.parseInt(process.env.PORT ?? "8080", 10);
const webPort = Number.parseInt(process.env.WEB_PORT ?? "8787", 10);
const adapter: AnyTextAdapter = anthropicText("claude-opus-4-6");
const imageAdapter: AnyTextAdapter = openaiText("gpt-4o");
const wss = new WebSocketServer({ port: wsPort });
const imageInputHtml = Bun.file(new URL("../public/image-input.html", import.meta.url));

// ── Planner tool schema ──

const planStepSchema = z.object({
	id: z.string().describe("Short kebab-case id like 'foundation' or 'roof'"),
	feature: z
		.string()
		.describe(
			"What this feature is — e.g. 'Stone brick foundation 12x10 at ground level'",
		),
	details: z
		.string()
		.describe(
			"Creative details for the executor — materials, dimensions, coordinates, block states, etc.",
		),
});

const planSchema = z.object({
	planTitle: z.string().describe("Short title for the build"),
	origin: z.object({
		x: z.number().int(),
		y: z.number().int(),
		z: z.number().int(),
	}),
	steps: z
		.array(planStepSchema)
		.min(1)
		.describe("Ordered list of features to build"),
});

type Plan = z.infer<typeof planSchema>;

const submitPlanTool = toolDefinition({
	name: "submit_plan",
	description:
		"Submit the build plan. Call this exactly once with the complete feature list.",
	inputSchema: planSchema,
	outputSchema: z.object({ accepted: z.boolean() }),
});

// ── Per-connection session state ──

interface Message {
	role: "user" | "assistant";
	content: string;
}

interface Session {
	id: string;
	playerName?: string;
	pendingToolCalls: Map<string, (result: string) => void>;
	/** Planner chat history — persists across prompts so the AI knows what it already built. */
	plannerHistory: Message[];
	/** Set to true when the client sends a cancel message. Checked between tool calls / steps. */
	cancelled: boolean;
	/** Prevent overlapping prompt runs on the same player session. */
	processingPrompt: boolean;
}

const sessions = new Map<string, Session>();
const sessionsByPlayer = new Map<string, string>();
const socketsBySession = new Map<string, WebSocket>();

const getSuccessStatus = (success: boolean, message: string): string => JSON.stringify({ success, message });

const parsePlayerPos = (x: number, y: number, z: number) => ({
	x: Math.round(x),
	y: Math.round(y),
	z: Math.round(z),
});

const jsonResponse = (status: number, body: unknown) =>
	new Response(JSON.stringify(body), {
		status,
		headers: {
			"content-type": "application/json; charset=utf-8",
		},
	});

const collectTextOutput = async (stream: AsyncIterable<{ type: string; delta?: string }>) => {
	let text = "";
	for await (const chunk of stream) {
		if (chunk.type === "TEXT_MESSAGE_CONTENT") {
			text += chunk.delta ?? "";
		}
	}
	return text.trim();
};

const generateBuildPromptFromImage = async (image: File, notes: string) => {
	const arr = await image.arrayBuffer();
	const b64 = Buffer.from(arr).toString("base64");
	const mimeType = image.type || "image/png";

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
							mimeType,
						},
						metadata: { detail: "high" },
					},
				],
			},
		],
	});

	const generated = await collectTextOutput(stream);
	console.log('[IMAGE PROMPT] Generated prompt from image:\n', generated);
	if (!generated) throw new Error("Image model did not return a prompt.");
	return generated;
};

const sendError = (ws: WebSocket, content: string) => {
	ws.send(JSON.stringify({ type: "error", content }));
};

const runPromptPipeline = async (
	session: Session,
	ws: WebSocket,
	prompt: string,
	playerPos: { x: number; y: number; z: number },
) => {
	if (session.processingPrompt) {
		throw new Error("Build already in progress for this session.");
	}
	session.processingPrompt = true;

	try {
		let totalToolCount = 0;
		const t0 = performance.now();

		/** Buffer text deltas and send a text_content_complete when done. */
		let textBuffer = "";
		const bufferDelta = (delta: string) => {
			textBuffer += delta;
		};
		const flushText = () => {
			if (textBuffer) {
				ws.send(JSON.stringify({ type: "text_content_complete", content: textBuffer }));
				textBuffer = "";
			}
		};

		// ── STAGE 1: PLANNER ──
		console.log(`\n[PLANNER] Starting planner...`);
		console.log(`[PLANNER] Sending prompt to claude-opus-4-6`);
		ws.send(JSON.stringify({ type: "thinking" }));

		let planArgsJson = "";
		const plannerTool = submitPlanTool.server(async () => ({
			accepted: true,
		}));

		const plannerUserText = [
			`Player position: ${playerPos.x}, ${playerPos.y}, ${playerPos.z}`,
			`Build request: ${prompt}`,
		].join("\n");

		// Append the new user message to planner history.
		session.plannerHistory.push({
			role: "user",
			content: plannerUserText,
		});

		const tPlanner = performance.now();
		const plannerStream = chat({
			adapter,
			maxTokens: 16384,
			systemPrompts: [spatialprefixPrompt, plannerPrompt],
			messages: session.plannerHistory.map((m) => ({
				role: m.role as "user" | "assistant",
				content: [{ type: "text" as const, content: m.content }],
			})),
			tools: [plannerTool],
		});

		console.log(`[PLANNER] Streaming response...`);
		let currentToolName = "";
		let plannerChunks = 0;
		textBuffer = "";
		for await (const chunk of plannerStream) {
			plannerChunks++;
			if (chunk.type === "TEXT_MESSAGE_CONTENT") {
				process.stdout.write(chunk.delta);
				bufferDelta(chunk.delta);
				ws.send(JSON.stringify({ type: "delta", content: chunk.delta }));
			} else if (chunk.type === "TOOL_CALL_START") {
				flushText();
				currentToolName = (chunk as { toolName?: string }).toolName ?? "";
				if (currentToolName === "submit_plan") {
					console.log(
						`\n[PLANNER] Model called submit_plan — receiving plan args...`,
					);
					planArgsJson = "";
				}
			} else if (chunk.type === "TOOL_CALL_ARGS") {
				if (currentToolName === "submit_plan") {
					planArgsJson += (chunk as { delta?: string }).delta ?? "";
				}
			}
		}
		flushText();
		console.log();

		const plannerMs = performance.now() - tPlanner;
		console.log(
			`[PLANNER] Stream finished (${plannerChunks} chunks, ${(plannerMs / 1000).toFixed(1)}s)`,
		);

		if (!planArgsJson) throw new Error("Planner did not call submit_plan");
		console.log(`[PLANNER] Parsing plan (${planArgsJson.length} chars)...`);
		const plan: Plan = planSchema.parse(JSON.parse(planArgsJson));

		// Record the plan in history so the planner has context for follow-up requests.
		const planSummary = [
			`Plan: "${plan.planTitle}" at (${plan.origin.x}, ${plan.origin.y}, ${plan.origin.z})`,
			...plan.steps.map(
				(s) => `- ${s.id}: ${s.feature} — ${s.details.slice(0, 200)}`,
			),
		].join("\n");
		session.plannerHistory.push({
			role: "assistant",
			content: planSummary,
		});

		console.log(`[PLANNER] Plan validated: "${plan.planTitle}"`);
		console.log(
			`[PLANNER] Origin: (${plan.origin.x}, ${plan.origin.y}, ${plan.origin.z})`,
		);
		console.log(
			`[PLANNER] ${plan.steps.length} features (history: ${session.plannerHistory.length} messages):`,
		);
		for (const [j, step] of plan.steps.entries()) {
			console.log(`  ${j + 1}. [${step.id}] ${step.feature}`);
			console.log(
				`     Details: ${step.details.slice(0, 120)}${step.details.length > 120 ? "..." : ""}`,
			);
		}

		ws.send(
			JSON.stringify({
				type: "plan_ready",
				origin: plan.origin,
				stepCount: plan.steps.length,
			}),
		);

		// ── STAGE 2: EXECUTOR ──
		console.log(`\n${"─".repeat(60)}`);
		console.log(
			`[EXECUTOR] Starting execution of ${plan.steps.length} features`,
		);
		console.log(`${"─".repeat(60)}`);

		for (const [i, step] of plan.steps.entries()) {
			// Check for cancellation between steps
			if (session.cancelled) {
				console.log(`[${session.id}] Build cancelled by player at step ${i + 1}`);
				throw new Error("Build cancelled by player");
			}

			const stepIndex = i + 1;
			const totalSteps = plan.steps.length;
			let stepToolCount = 0;

			console.log(
				`\n[STEP ${stepIndex}/${totalSteps}] ▶ ${step.id}: ${step.feature}`,
			);
			ws.send(
				JSON.stringify({
					type: "step",
					content: `[${stepIndex}/${totalSteps}] ${step.feature}`,
				}),
			);

			// Wrap tools to forward calls to the mod.
			const tStep = performance.now();
			const tools = allTools.map((toolDef) =>
				toolDef.server(async (args) => {
					const toolCallId = crypto.randomUUID();
					stepToolCount++;
					totalToolCount++;
					console.log(
						`  [TOOL #${stepToolCount}] ${toolDef.name}(${JSON.stringify(args)})`,
					);

					ws.send(
						JSON.stringify({
							type: "tool_call",
							toolCallId,
							name: toolDef.name,
							args,
						}),
					);

					console.log(
						`  [WAITING] Waiting for mod to execute ${toolDef.name}...`,
					);
					const rawResult = await new Promise<string>((resolve) => {
						session.pendingToolCalls.set(toolCallId, resolve);
					});

					let result: { success?: boolean; message?: string };
					try {
						result = JSON.parse(rawResult);
					} catch {
						result = { success: false, message: "Invalid JSON" };
					}
					const status = result.success !== false ? "OK" : "FAIL";
					console.log(
						`  [${status}] ${toolDef.name} -> ${result.message ?? ""}`,
					);
					return result;
				}),
			);

			const executorUserText = [
				`Player request: ${prompt}`,
				`Build origin: ${plan.origin.x}, ${plan.origin.y}, ${plan.origin.z}`,
				`Recent completed steps (latest up to 2): ${
					plan.steps
						.slice(Math.max(0, i - 2), i)
						.map((s, idx) => `${Math.max(0, i - 2) + idx + 1}. ${s.id}: ${s.feature}`)
						.join(" | ") || "none"
				}`,
				``,
				`Current step (${stepIndex}/${totalSteps}):`,
				`Feature: ${step.feature}`,
				`Details: ${step.details}`,
			].join("\n");

			console.log(`  [EXECUTOR] Sending step to model...`);
			const stream = chat({
				adapter,
				maxTokens: 16384,
				systemPrompts: [spatialprefixPrompt, executorPrompt],
				messages: [
					{
						role: "user",
						content: [{ type: "text", content: executorUserText }],
					},
				],
				tools,
			});

			let aiText = "";
			textBuffer = "";
			console.log(`  [EXECUTOR] Streaming response...`);
			for await (const chunk of stream) {
				if (chunk.type === "TEXT_MESSAGE_CONTENT") {
					aiText += chunk.delta;
					process.stdout.write(chunk.delta);
					bufferDelta(chunk.delta);
				}
			}
			flushText();
			if (aiText) console.log();

			const stepMs = performance.now() - tStep;
			console.log(
				`  [STEP DONE] ${stepToolCount} commands in ${(stepMs / 1000).toFixed(1)}s (running total: ${totalToolCount})`,
			);
		}

		// ── STAGE 3: FINALIZER ──
		console.log(`\n${"─".repeat(60)}`);
		console.log(`[FINALIZER] Generating summary...`);

		const finalizerUserText = [
			`Player request: ${prompt}`,
			`Completed: ${plan.steps.length} features, ${totalToolCount} total commands`,
			`Features built: ${plan.steps.map((s) => s.feature).join(", ")}`,
		].join("\n");

		const tFinalizer = performance.now();
		const finalizerStream = chat({
			adapter,
			maxTokens: 1024,
			systemPrompts: [finalizerPrompt],
			messages: [
				{
					role: "user",
					content: [{ type: "text", content: finalizerUserText }],
				},
			],
		});

		let finalText = "";
		for await (const chunk of finalizerStream) {
			if (chunk.type === "TEXT_MESSAGE_CONTENT") {
				finalText += chunk.delta;
			}
		}
		console.log(
			`[FINALIZER] "${finalText.trim()}" (${((performance.now() - tFinalizer) / 1000).toFixed(1)}s)`,
		);
		if (finalText) {
			ws.send(JSON.stringify({ type: "text_content_complete", content: finalText }));
		}

		const elapsed = ((performance.now() - t0) / 1000).toFixed(1);
		console.log(`\n${"=".repeat(60)}`);
		console.log(
			`[DONE] ${totalToolCount} commands across ${plan.steps.length} features in ${elapsed}s`,
		);
		console.log(`${"=".repeat(60)}\n`);
		ws.send(
			JSON.stringify({
				type: "done",
				toolCount: totalToolCount,
				completedSteps: plan.steps.length,
			}),
		);
	} catch (err: unknown) {
		console.error("[ERROR]", err);
		sendError(ws, (err as Error).message);
	} finally {
		session.processingPrompt = false;
	}
};

// ── Server ──

wss.on("connection", (ws) => {
	const sessionId = crypto.randomUUID().slice(0, 8);
	const session: Session = {
		id: sessionId,
		playerName: undefined,
		pendingToolCalls: new Map(),
		plannerHistory: [],
		cancelled: false,
		processingPrompt: false,
	};
	sessions.set(sessionId, session);
	socketsBySession.set(sessionId, ws);
	console.log(`[${sessionId}] Client connected (${sessions.size} active)`);

	ws.on("message", async (raw) => {
		const msg = JSON.parse(raw.toString());

		if (msg.type === "register") {
			const playerName = String(msg.playerName ?? "").trim();
			if (!playerName) {
				sendError(ws, "Missing playerName in register message.");
				return;
			}

			session.playerName = playerName;
			sessionsByPlayer.set(playerName, sessionId);
			console.log(`[${sessionId}] Registered player ${playerName}`);
			return;
		}

		// Tool result from the mod.
		if (msg.type === "tool_result" && msg.toolCallId) {
			const resolve = session.pendingToolCalls.get(msg.toolCallId);
			if (resolve) {
				resolve(msg.result ?? getSuccessStatus(false, "Missing result"));
				session.pendingToolCalls.delete(msg.toolCallId);
			}
			return;
		}

		// Cancel request from the mod.
		if (msg.type === "cancel") {
			console.log(`[${sessionId}] Client requested cancel`);
			session.cancelled = true;
			// Resolve any pending tool calls so the executor loop unblocks
			for (const [_id, resolve] of session.pendingToolCalls) {
				resolve(getSuccessStatus(false, "Build cancelled by player"));
			}
			session.pendingToolCalls.clear();
			return;
		}

		if (msg.type !== "prompt") return;

		const prompt = String(msg.content ?? "").trim();
		if (!prompt) {
			sendError(ws, "Prompt cannot be empty.");
			return;
		}

		const playerPos = parsePlayerPos(
			Number(msg.playerPosition?.x ?? 0),
			Number(msg.playerPosition?.y ?? 64),
			Number(msg.playerPosition?.z ?? 0),
		);
		console.log(`\n${"=".repeat(60)}`);
		console.log(`[${sessionId}] "${prompt}"`);
		console.log(
			`[${sessionId}] Position: (${playerPos.x}, ${playerPos.y}, ${playerPos.z})`,
		);
		console.log(`${"=".repeat(60)}`);

		// Reset cancel flag at the start of each prompt
		session.cancelled = false;
		if (session.processingPrompt) {
			sendError(ws, "Build already in progress. Wait or cancel first.");
			return;
		}

		void runPromptPipeline(session, ws, prompt, playerPos);
	});

	ws.on("close", () => {
		if (session.playerName) {
			sessionsByPlayer.delete(session.playerName);
		}
		socketsBySession.delete(sessionId);
		sessions.delete(sessionId);
		console.log(`[${sessionId}] Client disconnected (${sessions.size} active)`);
	});
});

Bun.serve({
	port: webPort,
	fetch: async (request) => {
		const url = new URL(request.url);

		if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/image-input")) {
			return new Response(imageInputHtml, {
				headers: {
					"content-type": "text/html; charset=utf-8",
				},
			});
		}

		if (request.method === "GET" && url.pathname === "/health") {
			return jsonResponse(200, { ok: true });
		}

		if (request.method === "POST" && url.pathname === "/api/image-to-build") {
			try {
				const form = await request.formData();
				const playerName = String(form.get("playerName") ?? "").trim();
				const notes = String(form.get("notes") ?? "").trim();
				const x = Number(form.get("x"));
				const y = Number(form.get("y"));
				const z = Number(form.get("z"));
				const image = form.get("image");

				if (!playerName) {
					return jsonResponse(400, { error: "Missing player name." });
				}

				if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) {
					return jsonResponse(400, { error: "Invalid origin coordinates." });
				}

				if (!(image instanceof File)) {
					return jsonResponse(400, { error: "Image file is required." });
				}

				if (!image.type.startsWith("image/")) {
					return jsonResponse(400, { error: "Uploaded file must be an image." });
				}

				const sessionId = sessionsByPlayer.get(playerName);
				if (!sessionId) {
					return jsonResponse(404, {
						error:
							"No active in-game session for that player. Connect in Minecraft first.",
					});
				}

				const session = sessions.get(sessionId);
				const ws = socketsBySession.get(sessionId);
				if (!session || !ws) {
					return jsonResponse(404, { error: "Player session was disconnected." });
				}

				if (session.processingPrompt) {
					return jsonResponse(409, {
						error: "Build already in progress. Wait for it to finish or /vb cancel.",
					});
				}

				const generatedPrompt = await generateBuildPromptFromImage(image, notes);
				session.cancelled = false;
				const playerPos = parsePlayerPos(x, y, z);

				console.log(`\n${"=".repeat(60)}`);
				console.log(
					`[${session.id}] [IMAGE] ${playerName} submitted image prompt from (${playerPos.x}, ${playerPos.y}, ${playerPos.z})`,
				);
				console.log(`${"=".repeat(60)}`);

				void runPromptPipeline(session, ws, generatedPrompt, playerPos);

				return jsonResponse(200, {
					message: "Prompt generated and sent to your game session.",
					generatedPrompt,
				});
			} catch (err: unknown) {
				console.error("[IMAGE API ERROR]", err);
				return jsonResponse(500, {
					error: (err as Error).message || "Failed to process image.",
				});
			}
		}

		return new Response("Not found", { status: 404 });
	},
});

console.log(`WebSocket server running on ws://localhost:${wsPort}`);
console.log(`Image web app running on http://localhost:${webPort}/image-input`);
