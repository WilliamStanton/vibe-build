import { chat } from "@tanstack/ai";
import type { WebSocket } from "ws";
import executorPrompt from "../prompts/executor.system.txt";
import finalizerPrompt from "../prompts/finalizer.system.txt";
import plannerPrompt from "../prompts/planner.system.txt";
import spatialprefixPrompt from "../prompts/spatialprefix.system.txt";
import { adapter } from "./config";
import { type Plan, planSchema, submitPlanTool } from "./schema";
import { allWorldEditTools as allTools } from "./tools";
import type { Session } from "./types";
import { sendError } from "./utils";

export const runPromptPipeline = async (
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
				ws.send(
					JSON.stringify({
						type: "text_content_complete",
						content: textBuffer,
					}),
				);
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
				console.log(
					`[${session.id}] Build cancelled by player at step ${i + 1}`,
				);
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
						.map(
							(s, idx) =>
								`${Math.max(0, i - 2) + idx + 1}. ${s.id}: ${s.feature}`,
						)
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
			ws.send(
				JSON.stringify({ type: "text_content_complete", content: finalText }),
			);
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
