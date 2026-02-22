# server

Runs two services:
- WebSocket build server on `PORT` (default `8080`) for the Minecraft mod
- Image input web app on `WEB_PORT` (default `8787`) at `http://localhost:8787/image-input`

Environment variables:

```bash
PORT=8080
WEB_PORT=8787
OPENAI_API_KEY=...
ANTHROPIC_API_KEY=...
```

To install dependencies:

```bash
bun install
```

To run:

```bash
bun run index.ts
```

This project was created using `bun init` in bun v1.3.9. [Bun](https://bun.com) is a fast all-in-one JavaScript runtime.
