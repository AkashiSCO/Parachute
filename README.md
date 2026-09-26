# Create:Parachute (1.1.3b)

A parachute mod for **Create** and **Sable physics**.

Build a parachute onto your vehicle, deploy it with a redstone pulse, and glide down safely.

## Features

- Drop a folder into `<game root>/parachute/local/` and it shows up in the selection GUI instantly
  - `parachute/`, `parachute/local/` and `parachute/server/` are created on first launch.
    Only the default **mushroom** parachute is written into `parachute/local/` (it is the fallback used when
    a deployed parachute is missing); the other built-ins stay inside the jar and are never auto-exported,
    so deleting a parachute folder actually deletes it
  - **BlockBench**: `<name>.bbmodel` + `.png` — Java entity models and Bedrock edition models
  - **Blender / OBJ**: `<name>.obj` + `<name>.mtl` + `textures/*.png`, exported with Blender's default
    OBJ settings (**Forward: -Z, Up: Y**, scale 1 unit = 1 block, origin at the attach point).
    Recommended: enable *Geometry → Triangulated Mesh* on export (n-gons are fan-triangulated otherwise,
    which is wrong for concave faces), and give each part its own object (`o`) — every object becomes a
    bone, so parts can later be driven by an animation.
    If both a `.bbmodel` and a `.obj` exist in the folder, the `.bbmodel` wins.
    - **Vertex normals**: the exported `vn` are used as-is. If a model only carries *per-face* normals
      (typical for DCS conversions, where curved surfaces then render as visible triangles), set
      `visual.objSmoothAngle` (default `60`) to recompute them from the geometry: faces within the angle
      are angle-weighted averaged, sharper edges stay hard. `0` disables it and keeps the file's normals.
  - **Shaders (Iris)**: `visual.shadersGeometry` selects the geometry/cull strategy for opaque layers and
    applies both with and without a shader pack, so the model looks the same either way:
    `SINGLE_CULL` (default) = one copy + back-face culling (half the vertices; the shaded fragments always
    face the camera, so lighting is always correct, but a one-sided thin part is invisible from behind),
    `DOUBLE` = both windings + culling (both sides visible, double the vertices), `SINGLE_NO_CULL` = one copy
    without culling, which needs a shader that flips back-facing normals (this mod's own shader does; for
    packs see below). Translucent layers always use two copies + culling.
    - Some packs (e.g. Photon) shade entities with a `flat` — i.e. per-triangle — normal, so any
      smooth-shaded model still shows facets under them; that can only be fixed in the pack (add an
      interpolated normal varying for lighting).
  - **Performance**: models with 20k+ triangles (the OBJ ones) are baked into GPU vertex buffers when
    they load, so a 980k-triangle model draws at 60+ fps instead of ~9. Results are per-layer identical to
    the normal path (light and dye are baked per variant); pass `-Dparachute.debug.nogpu=true` to compare
    against per-frame emission.
- **Controller GUI** (right-click the block or the pack):
  - **Drag** — drag coefficient of the canopy (higher = slows you down faster)
  - **RotDrag** — rotational damping (higher = spins stop faster)
  - **Cutaway** — low-speed detach threshold in m/s; below this speed the chute auto-retracts when enabled
  - **Low** — toggle low-speed auto-detach on/off
  - **RS** — redstone behaviour: a pulse deploys the chute; with RS on, another pulse retracts it
  - **Save / M / P / R / Lock** — apply settings, model offset, pivot offset, rotation, lock rotation.
    **`P` (pivot)** shifts the pivot *relative to the model*: it is applied after the rotation, so the
    pivot point (= where the player sits in seat mode) stays put and the model slides on it.
    **`M` (model offset)** moves the pivot *and* the model together (applied before the rotation, in
    block axes). Rotation is Euler YXZ (`Y` = yaw, `X` = pitch, `Z` = roll) and the model space has the
    chute top on `+Y`, so the axes read like the world. With **F3 + B** on you get the pivot/seat marker
    (yellow in seat mode) and the model's axes
- **Parachute selection GUI** — scrollable list of parachutes, open the game's `parachute/` folder in your file explorer
- **Dye support** — recolor a placed parachute with any dye; restore the original with an axe
- **Seat parachute pack** — a wrench (Create's or any `c:tools/wrench`) or a debug stick right-click toggles a pack
  between the normal form and a cushion you can sit on. Empty-hand right-click sits down (sneak to get off),
  Shift + right-click opens the usual controller GUI, and everything else (deploy/retract, dye, redstone,
  comparator output, the GUI) works exactly the same; breaking either form drops the ordinary pack item.
  The player sits exactly on the pack's **pivot point**, so the pivot offset (`P` in the transform GUI) is
  the one knob for where the cushion is: with **F3 + B** on, seat mode draws that point as a yellow marker.
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
