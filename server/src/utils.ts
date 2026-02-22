import { networkInterfaces } from "node:os";
import type { WebSocket } from "ws";

export const getLanIPv4 = (): string | null => {
	const interfaces = networkInterfaces();
	for (const entries of Object.values(interfaces)) {
		for (const entry of entries ?? []) {
			if (entry.family === "IPv4" && !entry.internal) {
				return entry.address;
			}
		}
	}
	return null;
};

export const getSuccessStatus = (success: boolean, message: string): string =>
	JSON.stringify({ success, message });

export const parsePlayerPos = (x: number, y: number, z: number) => ({
	x: Math.round(x),
	y: Math.round(y),
	z: Math.round(z),
});

export const jsonResponse = (status: number, body: unknown) =>
	new Response(JSON.stringify(body), {
		status,
		headers: {
			"content-type": "application/json; charset=utf-8",
		},
	});

export const sendError = (ws: WebSocket, content: string) => {
	ws.send(JSON.stringify({ type: "error", content }));
};
