# Infinite parkour

Jumps appear ahead of each player, a few at a time, and get harder the further they go. Every player gets their own lane. The score is how many jumps they made before falling.

## Set it up

1. Stand where the run should start: `/game create parkour <name>`.
2. Optional: keep it in a box with `/game edit <name> corner1` and `corner2`. Lanes share the box (9 blocks wide each) and the jumps turn at its walls. Without a box, lanes go off along +X from the start and jumps along +Z without end.
3. Put an entrance where players should get in: `/game edit <name> entrance add`.
4. Move the start later with `/game edit <name> start`.

## Settings

- **Blocks**: ids or `#tags`, comma-separated, mixed at random.
- **Gets harder**: easy, normal, hard or extreme. Levels add jumps up and down, diagonal jumps, longer gaps, slabs, then glass panes and iron bars. Only jumps a sprinting player can make, never longer than the longest gap.
- **Players at once** (lanes), the space between lanes without a box, jumps shown ahead, longest gap.

## Rewards

- **A reward every … jumps** with its own commands (`{jumps}`, `{player}`, `{game}`), also the Rewards trigger "Makes jumps in parkour".
- Commands when a run ends (`{score}` = jumps made, only with at least one).

> Parkour doesn't put inventories aside. Give the area a game claim (Claims page) if you want that.
