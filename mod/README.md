# VibeBuild Fabric Mod - In-game builder runtime 🎮
---

This mod is the Minecraft-side execution layer for VibeBuild.

It connects to the backend, executes WorldEdit tool calls, keeps players in a dedicated review dimension, and lets them place results back home with ghost preview controls.

## Quick run 🚀

```bash
bash ./gradlew build
```

Install jar:

- `build/libs/vibe-build-1.0.0.jar`

Required runtime mods:

- Fabric API
- WorldEdit for Fabric (Minecraft 1.21.11)

## In-game flow 🧭

1. `/vb <prompt...>` or `/vb image`
2. Mod streams backend progress and executes tool calls
3. Player reviews build in the build dimension
4. `/vb confirm` activates ghost preview in the original world
5. Player places with left click (or cancels)

## Commands 🎮

| Command | Purpose |
| --- | --- |
| `/vb <prompt...>` | Build from text prompt |
| `/vb image` | Start image mode workflow |
| `/vb confirm` | Accept review and activate placement preview |
| `/vb cancel` | Cancel current session or preview |
| `/vb connect` | Connect to backend manually |
| `/vb disconnect` | Disconnect from backend |

## Ghost preview controls 👻

- left click: place
- `R`: rotate clockwise
- `[`: rotate counter-clockwise
- `Page Up` / `Page Down`: move up/down

## What happens on confirm ✅

- Build is copied into WorldEdit clipboard
- Player returns to original world
- Ghost preview activates at player position
- Left click pastes with rotation and height adjustment
