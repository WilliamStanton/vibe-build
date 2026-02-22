# server

Runs two services:
- WebSocket build server on `PORT` (default `8080`) for the Minecraft mod
- Image input web app on `WEB_HOST:WEB_PORT` (defaults `0.0.0.0:8787`) at `/image-input`

Environment variables:

```bash
PORT=8080
WEB_HOST=0.0.0.0
WEB_PORT=8787
OPENAI_API_KEY=...
ANTHROPIC_API_KEY=...
```

Phone access notes:
- Keep your phone and PC on the same Wi-Fi network.
- Allow inbound traffic on `WEB_PORT` (8787) in your firewall for Private networks.
- Run `/vb image` in game, then scan the QR code shown on the page to open the same prefilled form on your phone.
- If the browser URL says `localhost`, the page automatically tries to switch the QR/share link to your LAN IP.

To install dependencies:

```bash
bun install
```

To run:

```bash
bun run index.ts
```

This project was created using `bun init` in bun v1.3.9. [Bun](https://bun.com) is a fast all-in-one JavaScript runtime.
