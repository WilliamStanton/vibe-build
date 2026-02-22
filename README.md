# VibeBuild - Vibe coding, but for Minecraft
---

VibeBuild turns one prompt or image into a complete build in just minutes with no user input required.

Player can place the result back into their world with ghost preview controls, or continue prompting to build more complex structures.

## Agent Crew

VibeBuild uses a multi-agent pipeline so each stage does one job well.

| Agent | Job | Player benefit |
| --- | --- | --- |
| Image agent | Converts a reference image + notes into a build-ready prompt | Easier input when words are not enough |
| Planner agent | Creates a structured build plan with origin and ordered steps | Coherent, high-quality build sequence |
| Executor agent | Calls WorldEdit tools step-by-step and adapts from tool results | Fast and reliable in-game construction |
| Finalizer agent | Produces a clear summary of what was built | Better transparency and confidence |

## Build modes

VibeBuild has two input modes:

- Text mode (`/vb <prompt...>`): direct natural-language prompt from in-game chat.
- Image mode (`/vb image`): opens a webpage or scans QR code, where you upload a reference image + optional notes, then the image agent sends it to be built.

## How it works

1. Player chooses their mode.
2. Planner Agent creates a structured plan.
3. Executor Agent runs steps via tool calls sent to the Minecraft mod via WebSocket.
4. Player reviews, adds follow-up prompts if desired, confirms, and places via ghost preview.

Executor context is token-optimized:

- planner history keeps a plan summary for follow-up prompts
- each executor call includes current step + up to 2 previous completed steps (window)


## In-game commands

| Command | Purpose |
| --- | --- |
| `/vb <prompt...>` | Generate and build from text |
| `/vb image` | Start separate image upload mode (image + notes -> generated build prompt) |
| `/vb confirm` | Accept reviewed build and enter placement preview |
| `/vb cancel` | Cancel current flow and return |
| `/vb connect` | Manual reconnect |
| `/vb disconnect` | Disconnect |

Ghost preview controls after `/vb confirm`:

- left click: place
- `R`: rotate clockwise
- `[`: rotate counter-clockwise
- `Page Up` / `Page Down`: move up/down

## Requirements

- Java 21
- Bun 1.3+
- Minecraft 1.21.11 with Fabric Loader 0.18.4+
- Fabric API
- WorldEdit for Fabric (runtime dependency)
- ANTHROPIC_API_KEY

## Quick start

### 1) Start backend

```bash
cd server
bun install
export ANTHROPIC_API_KEY=your_key_here
bun run start
```ws://localhost:8080`.

Defaults:

- WebSocket: `ws://localhost:8080`
- Image web app: `http://localhost:8787/image-input`

### 2) Build and install mod

```bash
cd mod
bash ./gradlew build
```

Install:

- `mod/build/libs/vibe-build-1.0.0.jar`
- plus Fabric API and WorldEdit (matching MC 1.21.11)

### 3) Launch Minecraft

When you join a world, the mod auto-connects to the websocket: `ws://localhost:8080`.