import type { AnyTextAdapter } from "@tanstack/ai";
import { anthropicText } from "@tanstack/ai-anthropic";

export const wsPort = Number.parseInt(process.env.PORT ?? "8080", 10);
export const webPort = Number.parseInt(process.env.WEB_PORT ?? "8787", 10);
export const webHost = process.env.WEB_HOST?.trim() || "0.0.0.0";

export const adapter: AnyTextAdapter = anthropicText("claude-opus-4-6");
export const imageAdapter: AnyTextAdapter = anthropicText("claude-sonnet-4-5");

export const imageInputHtml = Bun.file(
	new URL("../public/image-input.html", import.meta.url),
);
