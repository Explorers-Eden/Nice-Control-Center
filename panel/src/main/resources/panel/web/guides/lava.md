# Rising lava

Lava fills the box from its floor up, a layer at a time and faster each layer. Touching it is out; when it reaches the top, everyone still up made it. The score is how long they lasted.

## Set it up

1. `/game create lava <name>` at the bottom, where players appear.
2. The box around the tower or cave: `/game edit <name> corner1` and `corner2`.
3. An entrance: `/game edit <name> entrance add`.

## Settings

- **Seconds before the next layer** at the start, **how much faster** each layer, **the fastest**.

> The lava only goes where there's air and doesn't flow. Afterwards all lava and fire in and near the box go (also after a crash), so build with blocks that don't burn and keep your own lava away from it.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
