import * as QRCode from "qrcode";
import { imageInputHtml, webPort } from "./config";
import { generateBuildPromptFromImage } from "./image";
import { runPromptPipeline } from "./pipeline";
import { sessions, sessionsByPlayer, socketsBySession } from "./session";
import { getLanIPv4, jsonResponse, parsePlayerPos } from "./utils";

// ── Route handler type ──

type RouteHandler = (
	request: Request,
	url: URL,
) => Promise<Response> | Response;

interface Route {
	method: string;
	path: string | string[];
	handler: RouteHandler;
}

// ── Route definitions ──

const routes: Route[] = [
	{
		method: "GET",
		path: ["/", "/image-input"],
		handler: () =>
			new Response(imageInputHtml, {
				headers: { "content-type": "text/html; charset=utf-8" },
			}),
	},

	{
		method: "GET",
		path: "/health",
		handler: () => jsonResponse(200, { ok: true }),
	},

	{
		method: "GET",
		path: "/api/network",
		handler: () => jsonResponse(200, { lanIp: getLanIPv4(), webPort }),
	},

	{
		method: "POST",
		path: "/api/image-to-build",
		handler: async (request) => {
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
					return jsonResponse(400, {
						error: "Uploaded file must be an image.",
					});
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
					return jsonResponse(404, {
						error: "Player session was disconnected.",
					});
				}

				if (session.processingPrompt) {
					return jsonResponse(409, {
						error:
							"Build already in progress. Wait for it to finish or /vb cancel.",
					});
				}

				const generatedPrompt = await generateBuildPromptFromImage(
					image,
					notes,
				);
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
		},
	},

	{
		method: "GET",
		path: "/api/qr",
		handler: async (_request, url) => {
			const text = url.searchParams.get("text")?.trim() ?? "";
			if (!text) {
				return jsonResponse(400, { error: "Missing text query parameter." });
			}

			if (text.length > 2048) {
				return jsonResponse(400, { error: "QR text too long." });
			}

			let parsed: URL;
			try {
				parsed = new URL(text);
			} catch {
				return jsonResponse(400, { error: "QR text must be a valid URL." });
			}

			if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
				return jsonResponse(400, { error: "QR URL must use http or https." });
			}

			try {
				const svg = await QRCode.toString(text, {
					type: "svg",
					width: 280,
					margin: 1,
					errorCorrectionLevel: "M",
				});

				return new Response(svg, {
					status: 200,
					headers: {
						"content-type": "image/svg+xml; charset=utf-8",
						"cache-control": "no-store",
					},
				});
			} catch (err: unknown) {
				console.error("[QR API ERROR]", err);
				return jsonResponse(500, { error: "Failed to generate QR code." });
			}
		},
	},
];

// ── Router ──

const matchRoute = (route: Route, method: string, pathname: string) => {
	if (route.method !== method) return false;
	if (Array.isArray(route.path)) return route.path.includes(pathname);
	return route.path === pathname;
};

export const handleRequest = async (request: Request): Promise<Response> => {
	const url = new URL(request.url);

	const route = routes.find((r) => matchRoute(r, request.method, url.pathname));
	if (route) return route.handler(request, url);

	return new Response("Not found", { status: 404 });
};
