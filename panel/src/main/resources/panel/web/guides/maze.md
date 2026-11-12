# Maze

A generated maze, built new every day or after each finished run. Players find the way to the goal; the best time wins.

## Set it up

1. Stand where the maze's corner should be: `/game create maze <name>`.
2. Size: `/game edit <name> size <cells>` (4–256), then `/game edit <name> build`. Big ones take a few seconds.
3. An entrance: `/game edit <name> entrance add`.

Build it again after changing the size, path width, crossroads, blocks, light or chests.

## Settings

- **Path width** (1–5), **wall height**, **light** of the paths (0–15).
- **Wall, floor and ceiling blocks** (ids or `#tags`, mixed at random; one named twice comes twice as often). No ceiling = open sky.
- **Blocks under the goal**; the goal has an exit ring and an "Exit" label in the game's colour.
- **Crossroads** (12 % by default, up to 50 %): extra openings, mostly T-junctions, so ways meet and loop and there's more than one way through.
- **Chests** in a share of the dead ends, with a loot table.
- **Mobs** roaming while someone runs it: they come out 12–32 blocks from a runner and go when nobody runs it.

## Good to know

- Corridors turn more often than they go straight, never more than 3 cells straight while they could turn.
- Building clears mobs, vehicles and items inside (not players, NPCs, pets, named mobs, armor stands, frames or labels); players inside go to its start.

> Mazes don't put inventories aside. Give the area a game claim (Claims page) if you want that.
