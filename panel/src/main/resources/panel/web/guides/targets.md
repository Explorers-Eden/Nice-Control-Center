# Target practice

Target blocks appear in the box; players shoot them before they vanish. A bow and arrows are handed out, arrows refilled.

## Set it up

1. `/game create targets <name>` where players shoot from.
2. The box: `/game edit <name> corner1` and `corner2`.
3. An entrance: `/game edit <name> entrance add`.

## Settings

- **Targets appear** on the box's walls and floor, or anywhere in it (floating).
- **Seconds a target stays**, **targets at once**.

A hit scores 1 + a point per 10 blocks from the spawn, a bullseye (the middle of the face) twice that. Targets only go where there's air and are taken away again.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
