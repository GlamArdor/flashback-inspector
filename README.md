# Flashback Inspector

Puts into a Flashback replay what was never there: your inventory and the contents of every container
you opened. During playback the inventory key opens them as a normal screen, with real tooltips,
enchantments, lore and stats, because the file holds the actual items.

Minecraft 1.21.6–1.21.8, Fabric. Requires [Flashback](https://modrinth.com/mod/flashback) 0.39.0+.

## Why

Flashback records a stream of packets, and container packets are on its ignore list (`IgnoredPacketSet`):
`ContainerSetContent`, `ContainerSetSlot`, `OpenScreen`, `SetPlayerInventory` and the rest. All that is
left of the inventory in a recording is the nine hotbar slots (and only with `recording.recordHotbar`
enabled), plus worn equipment, which arrives as a regular `SetEquipment` for every player.

This mod writes the missing parts into the same stream as its own packets.

## How it works

**Recording.** Every tick the inventory and the open container are compared with what was written last
time, and the changed slots go to `Recorder.writePacketAsync` as an ordinary clientbound custom payload.
The full state also goes into every Flashback snapshot through `Recorder.writeCustomSnapshot`, a method
Moulberry left empty with the comment "Mods can mixin here". Without it, seeking would show an empty
inventory: on seek Flashback rebuilds the world from the nearest snapshot.

**Playback.** `ReplayServer` forwards the custom payload to the viewer byte for byte, the client decodes
it the usual way, and the mod assembles the state for the current tick. The screen is opened by hooking
`MinecraftClient.setScreen`: in a replay the inventory key leads to the viewer's own empty inventory, and
the mod swaps it for its own. It catches the screen rather than the key press, so every path works,
macros included.

**Compatibility.** A replay recorded with the mod opens without it, without a single error. Every action
in a Flashback file is a separate block with its own length, so an unknown payload is read as opaque
bytes and dropped without touching the next packet.

## What gets recorded

| | Recorded |
|---|---|
| Your own inventory, armor, both hands | yes, in full, with all components |
| Containers you opened yourself | yes, all contents and every slot change |
| Other players' armor and hands | yes, Flashback already records this, works on old replays too |
| Other players' inventories | no, and it can't: the server never sends them to the client |
| Containers opened by others | no, for the same reason |

A container's contents are known from the tick it was opened. Before that there is nothing to show.

## Which containers

A container is any `HandledScreen` except the creative menu and the player's own inventory. In the
1.21.8 client hierarchy that means:

- **Storage**: chest, trapped chest, ender chest, barrel, shulker box, minecart with chest, boat with
  chest, and horse, donkey and llama inventories. Almost every server plugin GUI lands here as well,
  since they are built on the same `GenericContainerScreenHandler`.
- **Machines**: dispenser, dropper, hopper, minecart with hopper, furnace, blast furnace, smoker,
  brewing stand, crafter.
- **Workstations**: crafting table, anvil, smithing table, grindstone, stonecutter, loom, cartography
  table, enchanting table.
- **Other**: beacon, villager trading (only the three trade slots; the offer list travels separately in
  the protocol and is not recorded yet).

Not containers and not recorded: lectern, signs, a held book, command block, structure block. They have
no slots, they are ordinary screens.

Container size is computed as `slots − 36`: the player's own thirty-six slots are always added last in
a vanilla `ScreenHandler`. If there are fewer than thirty-six slots, the container is not recorded at
all. Better nothing than the player's inventory a second time under someone else's name.

## Controls

| | |
|---|---|
| Inventory key (`E`) | inspector for the player you are following; the container on the right, if one was open |
| `K` | container log, clicking an entry seeks the replay to that tick |

The mod reads its own data straight out of the replay file when the replay opens, so the log lists
every container opened in the whole recording from the start, and the inventory is right at any tick,
including right after a proxy moves you from a lobby to the server behind it.

The container mode is set in the config: **manual** (default) or **follow the recording**, where the
container opens and closes by itself on the same ticks. The second mode is handy for rewatching a scene
and gets in the way when working with the camera: while any game screen is open, the Flashback editor
does not take mouse input.

## Building

```
./gradlew build
```

`libs/flashback-*-api.jar` is a stripped copy of Flashback (classes only, no ffmpeg or natives), needed
for compilation only. It is not included in the build and is not for redistribution.

## License

LGPL-3.0-or-later.
