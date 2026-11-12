# How games work

Every game is made the same way: build the place in game, create the game there with one command, then follow the steps it gives you. Settings live on the game's card below; the card's **General** tab shows which steps are still to do.

## 1. Before the first game

- **Game hub spawn**: stand in your hub and type `/game spawn set`. After any game players land here, and players who log out or die in a game area (or anywhere in its dimension) come back here too.
- Switch **Games on** at the top of this page.

## 2. Make a game

1. Stand where the game should be (where players appear, or its corner for mazes and grids).
2. `/game create <type> <name>`: the type is one of the templates (`parkour`, `race`, `maze`, `waves`, `ghasts`, `colorfloor`, `anvils`, `treasure`, `fishing`, `moles`, `targets`, `gallery`, `chef`, `lava`, `heads`).
3. Follow the hints it answers with. Each template's guide lists its steps.

> The `<name>` is one word, used in all commands. Players see the **display name** (spaces allowed), set on the card or with `/game edit <name> title Sky Run`.

## 3. Ways in and out

| What | Command | What it does |
|---|---|---|
| Entrance | `/game edit <name> entrance add` | Stepping in for a second takes players to the game; the name and top 3 float above it. |
| Watch point | `/game edit <name> watch add` | Takes visitors to the spectator area. |
| Back point | `/game edit <name> back add` | The way out of the spectator area; inside a wave fight or quick round, 5 seconds on one leaves the game. |
| Spectator area | `/game edit <name> spectate [radius]` | Where the queue waits and players who are out watch; or a box with `spectate corner1` + `spectate corner2`. |

Every point also takes `remove` (the nearest within 5 blocks) and `clear`. The card lists them all and can take them away too.

## 4. Playing

- Step into an entrance, or `/game join <name>`; `/game leave` to stop.
- `/game top <name>` shows the best players.
- `/game try <name>` (wave fights and quick rounds) plays it alone, to test it.
- Ender pearls don't work in games. Everyone starts with full health and the hunger bar at 9.5 of 10.

## 5. Rewards

- **Points**: on the card's Rewards tab, a fixed amount and an amount per score.
- **Commands** on start, finish, per wave and so on, with `{player}`, `{score}` and `{game}`. They can do sums: `{score*2}`, `{score/10}`, `{wave+5}` (rounded), e.g. `points give {player} {score*2}`.
- **Rewards page**: rules on "Finishes a game", "Sets a personal best" and each game's own triggers can give anything.

## 6. Keeping it safe

- Explosions break no blocks inside any game's box.
- Wave fights, quick rounds and dungeons put inventories aside themselves. For parkour, race and maze, put the area in a server claim with game-area options (Claims page).
- **Music**: every game but the head hunt can loop a jukebox song for its players, and another one in the spectator area.
- **Mob pools** (wave fights, ghasts, colour floor, gallery, maze): one line per mob like `/summon`: `zombie`, `skeleton x2`, `husk {…}`, `npc:knight`. Add `w5` to make a line come five times as often (`w0` never).
