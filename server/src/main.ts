import { WebSocketServer } from "ws";
import { webHost, webPort, wsPort } from "./config";
import { handleRequest } from "./routes";
import { getLanIPv4 } from "./utils";
import { handleConnection } from "./websocket";

// ── WebSocket server ──
const wss = new WebSocketServer({ port: wsPort });
wss.on("connection", handleConnection);

// ── HTTP server ──
Bun.serve({
	hostname: webHost,
	port: webPort,
	fetch: handleRequest,
});

// ── Startup log ──
const localWebUrl = `http://localhost:${webPort}/image-input`;
const lanIp = getLanIPv4();
const lanWebUrl = lanIp ? `http://${lanIp}:${webPort}/image-input` : null;

console.log(`WebSocket server running on ws://localhost:${wsPort}`);
console.log(`Image web app running on ${localWebUrl}`);
if (webHost === "0.0.0.0" && lanWebUrl) {
	console.log(`Image web app LAN URL: ${lanWebUrl}`);
}
if (webHost === "0.0.0.0") {
	console.log(`Phone tip: open the LAN URL on the same Wi-Fi network.`);
}
