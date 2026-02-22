import type { WebSocket } from "ws";
import type { Session } from "./types";

export const sessions = new Map<string, Session>();
export const sessionsByPlayer = new Map<string, string>();
export const socketsBySession = new Map<string, WebSocket>();

export const createSession = (id: string): Session => ({
	id,
	playerName: undefined,
	pendingToolCalls: new Map(),
	plannerHistory: [],
	cancelled: false,
	processingPrompt: false,
});
