export interface Message {
	role: "user" | "assistant";
	content: string;
}

export interface Session {
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
