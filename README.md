# Create:Parachute (1.1.0+)

A parachute mod for **Create** and **Sable physics**.

Build a parachute onto your vehicle, deploy it with a redstone pulse, and glide down safely.

## Features

- Drop a folder into `<game root>/parachute/` containing a `.bbmodel` and a `.png`, and it shows up in the selection GUI instantly
  - The built-in parachute is placed into the `parachute` folder automatically when the mod loads
  - Supports Java entity models and Bedrock edition models from BlockBench
- **Controller GUI** (right-click the block or the pack):
  - **Drag** — drag coefficient of the canopy (higher = slows you down faster)
  - **RotDrag** — rotational damping (higher = spins stop faster)
  - **Cutaway** — low-speed detach threshold in m/s; below this speed the chute auto-retracts when enabled
  - **Low** — toggle low-speed auto-detach on/off
  - **RS** — redstone behaviour: a pulse deploys the chute; with RS on, another pulse retracts it
  - **Save / M / P / R / Lock** — apply settings, model offset, pivot offset, rotation, lock rotation
- **Parachute selection GUI** — scrollable list of parachutes, open the game's `parachute/` folder in your file explorer
- **Dye support** — recolor a placed parachute with any dye; restore the original with an axe
- **Multiplayer** — parachutes the player doesn't have locally fall back to the default mushroom

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Sable (required dependency)

## How to use

1. Place the parachute block — or hold the parachute pack and right-click first to pick which parachute it will place.
2. Right-click it with an empty hand to open the controller and tune the parameters (Drag / RotDrag / Cutaway / Low / RS).
3. Send a redstone pulse to deploy it; it will slow your fall (or your contraption) until you land or retract it.
4. Recolor it with any dye, or revert it with an axe.

## Building from source

```bash
./gradlew build
```

The built jar will be in `build/libs/`.
