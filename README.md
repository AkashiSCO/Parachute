# Create:Parachute (1.1.2)

A parachute mod for **Create** and **Sable physics**.

Build a parachute onto your vehicle, deploy it with a redstone pulse, and glide down safely.

## Features

- Drop a folder into `<game root>/parachute/local/` containing a `.bbmodel` and a `.png`, and it shows up in the selection GUI instantly
  - The built-in parachutes are placed into that folder automatically when the mod loads
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
- **Multiplayer** — every server gets its own folder on your machine, so joining a server never mixes its parachutes with another server's or with your own; parachutes the player doesn't have fall back to the default mushroom

## Folders

```
parachute/
├─ local/<name>/…                       your own parachutes (edit these, and these are what /parachute upload sends)
├─ server/<world uuid>/<name>/…         parachutes downloaded from that server (the uuid is generated per world save)
└─ <name>/…                             the server library on a dedicated server, and your own library when you host a world
```

While connected to someone else's server, that server's folder wins over `local/`; in single-player or while hosting a LAN world, only `local/` is used.

Server parachutes are shared with `/parachute`:

| Command | Who | What it does |
|---|---|---|
| `/parachute list [page]` | everyone | list the parachutes in the server library |
| `/parachute download [name]` | everyone | pull one parachute, or the whole library, into your own `parachute/server/<world uuid>/` |
| `/parachute upload [name]` | OP | send your `parachute/local` parachute(s) to the server library |
| `/parachute distribute [players]` | OP | queue the whole library for other players |
| `/parachute delete [name]` | OP | delete from the server library (with a confirm button) |

Downloads are queued per player and sent in batches (default 64 KiB per tick, `download.bytesPerTick` in the config), so a big library never floods a connection. `download.allowPlayerDownload` (default `true`) can restrict `list`/`download` to OPs.

The per-server folder is named after a UUID the server stores in its world save (`<world>/create_parachute/server-id.txt`) and sends to clients on login, so a server keeps the same folder even if its address or port changes. Connecting to a server that doesn't have the mod falls back to `server/<address>/`.

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
