# VibeBuild Backend - The AI build pipeline 🧠
---

This service is the brain: it turns text or image intent into a step-by-step Minecraft build and streams tool calls to the mod in real time.

## What it does (live loop) ⚡

1. Accepts a prompt from the mod (text or image mode).
2. Plans a structured build (`submit_plan`).
3. Executes each step with WorldEdit tool calls.
4. Streams progress back to the player.
5. Produces a clean completion summary.

## Agent crew 🤖

| Agent | Responsibility | Output |
| --- | --- | --- |
| Image agent | Convert reference image + notes into a build-ready prompt | High-signal prompt text |
| Planner agent | Create ordered step plan with origin and details | `submit_plan` args |
| Executor agent | Call tool schemas step-by-step via WebSocket | Tool calls + step results |
| Finalizer agent | Summarize what was built for the player | Final completion summary |

## Run locally 🚀

```bash
bun install
ANTHROPIC_API_KEY=your_key_here bun run start
```

Defaults:

- WebSocket server: `ws://localhost:8080`
- Image web app: `http://localhost:8787/image-input`

Dev mode:

```bash
bun run dev
```

## Interactive modes 🧭

- Text mode: the mod sends `/vb <prompt...>` directly over WebSocket
- Image mode: the image page posts a reference image + notes, which the image agent converts into a build prompt and dispatches to the active player session

## Message flow (at a glance) 🔄

```text
Minecraft Mod  ->  WebSocket  ->  Backend
  prompt            |           Planner
  tool_result        |           Executor -> tool_call -> mod
  cancel             |           Finalizer
```
