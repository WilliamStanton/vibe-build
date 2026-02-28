# VibeBuild - vibe coding, but for Minecraft 🧱

VibeBuild turns one prompt or image into a complete Minecraft build in minutes.

It now runs as a **fully in-mod serverless system**: no external backend process, no websocket bridge, no Bun runtime.

- [Showcase Gallery](SHOWCASE.md)

## What it can do 🤖

- Build structures from text with `/vb <prompt...>`
- Build redstone circuits with `/vb redstone <prompt...>`
- Build from image references with `/vb image [prompt]`
- Let you review in a dedicated build dimension
- Place back into your world with ghost preview controls

## How it works ⚙️

### Build flow

1. (Image mode only) converts image + notes into a build request
2. Planner creates a structured build plan
3. Executor performs tool calls step-by-step in the build dimension
4. Finalizer summarizes what was built
5. You review, then `/vb confirm` to place with ghost preview

### Redstone flow

1. Planner creates circuit subsystems and signal flow order
2. Executor runs redstone-focused and WorldEdit tools
3. Finalizer summarizes behavior and activation expectations

Both flows share session/review/preview systems, but redstone has its own planning and execution prompts.

## In-game commands 🎮

| Command | Purpose |
| --- | --- |
| `/vb <prompt...>` | Generate and build from text |
| `/vb redstone <prompt...>` | Generate and build a redstone circuit |
| `/vb image [prompt]` | Open native image picker and build from image + optional notes |
| `/vb apikey anthropic <key>` | Set Anthropic API key |
| `/vb apikey openai <key>` | Set OpenAI API key |
| `/vb model` | Show current model + provider |
| `/vb model <name>` | Switch model live in-game |
| `/vb confirm` | Accept reviewed build and enter placement preview |
| `/vb cancel` | Cancel current flow and return |

## Live model switching (Anthropic + OpenAI) 🔁

You can hot-swap models live in Minecraft chat:

- `/vb model` shows current model and provider
- `/vb model <name>` switches immediately
- provider is auto-detected from model id (`claude-*` => Anthropic, `gpt-*` / `o*` => OpenAI)
- the matching API key is selected automatically

## Ghost preview controls 👻

- left click: place
- `R`: rotate clockwise
- `[`: rotate counter-clockwise
- `Page Up` / `Page Down`: move up/down

## Requirements ✅

- Java 21
- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API
- WorldEdit for Fabric 7.4.0
- Anthropic and/or OpenAI API key

## Quick start 🚀

### 1) Build the mod

```bash
cd mod
./gradlew build
```

Output jar:

- `mod/build/libs/vibe-build-1.0.0.jar`

### 2) Install dependencies

Put these in your Minecraft `mods` folder:

- `vibe-build-1.0.0.jar`
- Fabric API
- WorldEdit for Fabric (MC 1.21.11)

### 3) Configure model access in game

```text
/vb apikey anthropic <key>
/vb apikey openai <key>   (only needed for OpenAI models)
/vb model <model-name>
```

Examples:

```text
/vb model claude-opus-4-6
/vb model gpt-5
```
