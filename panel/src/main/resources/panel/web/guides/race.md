# Race course

Players run, sail or fly through checkpoints in order, then reach the finish. The best time wins.

## Set it up

1. Stand at the start: `/game create race <name>`.
2. At each checkpoint, in order: `/game edit <name> checkpoint add <radius>`.
3. At the end: `/game edit <name> finish <radius>`.
4. An entrance: `/game edit <name> entrance add`.

## Settings

- **Race by**: running, boat or elytra.
- **Time limit** in seconds (0 = none).
- **Commands at each checkpoint** with `{checkpoint}`, `{player}`, `{game}`.

## Rewards

- Points for finishing (timed games get a fixed amount).
- Commands when they finish, `{score}` = their time.

> Races don't put inventories aside. Give the area a game claim (Claims page) if you want that.
