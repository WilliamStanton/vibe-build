import type { WebSocket } from "ws";
import { runPromptPipeline } from "./pipeline";
import {
	createSession,
	sessions,
	sessionsByPlayer,
	socketsBySession,
} from "./session";
import { getSuccessStatus, parsePlayerPos, sendError } from "./utils";

export const handleConnection = (ws: WebSocket) => {
	const sessionId = crypto.randomUUID().slice(0, 8);
	const session = createSession(sessionId);
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
};
