# Bosses

Built-in boss fights for wave arenas and dungeons. Write one like a mob:

- **Wave arenas:** add it to the arena's **Bosses** list (the panel has a picker under it), or put it in a
  hand-written wave. With "Boss wave every … waves", each boss wave brings the boss on its own.
- **Dungeons:** use it in a boss marker, for example `/dungeon tool marker boss:ncc:frost_warden`.
- **Trying one:** `/game boss <name>` summons it where you stand. `/game boss clear` takes it away again. Names can be
  written in full (`ncc:tempest`) or short (`tempest`).

Every boss:

- gets the wave arena's "% more health per wave" on its health, like the other wave mobs (its phases go by share of
  its health, so they still come at the same points)
- stays inside the fight box (the wave arena's box, the dungeon copy): flying bosses are turned back at the walls and
  ceiling instead of leaving small arenas
- drops nothing and gives no XP
- shows its boss bar only to the players of its game, and its chat lines go only to them
- is left alone by Nice Mob Manager
- leaves nothing behind: temporary blocks (powder snow, fire, bone walls, sculk) disappear again, and wave arenas are
  put back as they were after every wave

Its helpers (minions, swarms, shards) count as wave mobs: the wave isn't cleared until they're down too.

Health, damage and timings below are the current values. "Every N s" means while players are close enough.

## NCC bosses

### The Frost Warden — `ncc:frost_warden`
A giant stray with a bow, 220 health.
- **Freezing aura:** players within 12 blocks are slowed.
- **Icicles:** every 6 s a frost ring marks each player's spot, then an icicle falls there: 7 damage and slowness.
- **Powder snow:** every 9 s powder snow opens under the nearest player for 8 seconds.
- **At half health:** 4 Frost Guard strays appear, and a 6-second blizzard blinds and slows everyone.
- Players' arrows close to it fall to the ground.

### The Hive Queen — `ncc:hive_queen`
A huge bee, 180 health. She hovers some 6 blocks from the nearest player.
- **Swarm:** she starts with 6 Royal Guard bees. More come every 20 s when only 2 or fewer are left.
- **Healing:** she heals 4 health a second while any of her bees live. Kill the swarm first.
- **Dive:** every 8 s she dives at a player: 6 damage and poison.
- **Honey:** every 8 s honey drips at a player's spot and slows everyone there hard.
- **Below 40 %:** 6 more bees, and she flies faster.

### The Ember Colossus — `ncc:ember_colossus`
A giant magma cube, 150 health.
- **Leap and slam:** every 9 s flames mark a spot near a player, and it jumps there. The landing is a burning
  shockwave: 6 damage, knockback, fire, and a ring of fire.
- **Fireballs:** volleys of three small fireballs at players.
- **Molten trail:** short fire wherever it moves.
- **Fire rings and lava splashes:** flames warn where the lava lands: 6 damage and burning.
- **At two thirds and one third:** it bursts into Ember Shards (2, then 3, with 25 health each). The fight is won when
  every cube is down. After the second burst everything comes faster.
- Water hurts it.

### The Tempest — `ncc:tempest`
A big breeze, 160 health.
- **Gusts:** every 5 s three gusts are marked on the ground, then wind bursts go off there.
- **Lift:** every 8 s a player floats up for 2 seconds.
- **Whirlwind:** every 20 s it pulls everyone within 18 blocks in for 3 seconds. Then a shockwave throws them out:
  4 damage, and falling hurts too.
- Like any breeze it turns arrows away, so it's fought up close.

### The Drowned Admiral — `ncc:drowned_admiral`
A drowned captain with a trident and a banner hat, 200 health.
- **Tridents:** every 7 s three tridents fly at players.
- **Tide:** every 12 s a splash ring warns, then a shockwave hits everyone within 7 blocks for 5 damage and throws
  them back.
- **Crew:** 3 Drowned Crew at two thirds and again at one third.
- In water it regenerates and gets faster.

### The Bone Architect — `ncc:bone_architect`
A wither skeleton, 220 health.
- **Bone walls:** every 10 s it walls the nearest player in with bone, 3 blocks high, for 6 seconds.
- **Arrow rain:** every 8 s a ring marks a spot, then 12 arrows fall on it.
- **Risen dead:** every 9 s two Risen Bones skeletons climb out of the ground next to players (up to 6).

### The Creeper King — `ncc:creeper_king`
A giant charged creeper, 160 health. It never blows itself up.
- **Creeperlings:** every 8 s it drops 3 (up to 9). A Creeperling goes off when a player stays close for 1.5 seconds.
  The blast hurts but breaks nothing.
- **Stomp:** every 10 s a warning, then a shockwave: 5 damage.
- **At a quarter of its health:** a 10-second countdown. Deal 30 damage in that time and it fizzles. Otherwise it goes
  off in a huge blast (nothing broken) and is gone.

### The Echo Shepherd — `ncc:echo_shepherd`
A husk wrapped in sculk, 200 health.
- **Hunts by sound:** it finds players within 30 blocks, but sneaking ones only within 6.
- **Darkness:** every 12 s a pulse darkens the view of everyone within 30 blocks.
- **Sonic beam:** every 9 s a line on the ground marks it for a second, then it fires: 10 damage. Step aside.
- Sculk spreads where it walks.

### The Coven Matron — `ncc:coven_matron`
A witch, 180 health.
- **Potions:** every 4 s she throws one at a player: poison, slowness, weakness or harming. Her copies throw too.
- **Cauldron clouds:** every 7 s a lingering cloud at a player's feet.
- **The coven:** at two thirds and one third she swaps places with one of 3 copies of herself. A copy vanishes in
  smoke when hit. The real one glows for a moment when she's hit.

### The Phantom Monarch — `ncc:phantom_monarch`
A huge phantom, 160 health. The sun doesn't burn it.
- **Dives:** its shadow on the ground shows where it's diving.
- **Dazed:** after a dive that hits, it falls to the ground for 3 seconds. That's the time for swords. Whoever it hit
  is slowed and weakened.
- **Night court:** every 25 s 4 Night Courtier phantoms join (up to 8).

### The Piglin Warlord — `ncc:piglin_warlord`
A piglin brute with a golden axe, 240 health.
- **Charge:** every 9 s a line of embers marks it, then it rushes along it: 9 damage and a big knockback.
- **Gold dust:** every 11 s everyone within 6 blocks is blinded and sickened.
- **Warband:** 3 piglins at half health.
- **At a quarter:** it becomes the Rotting Warlord, a zombified piglin with the same health. It's faster but hits
  less hard.

### The Ender Stalker — `ncc:ender_stalker`
An enderman, 200 health.
- **Blink:** every 8 s it teleports behind the nearest player and strikes: 7 damage.
- **Swap:** every 15 s two players trade places.
- **Brood:** every 12 s 3 endermites (up to 6).
- **Stare:** while someone looks straight at it, it gets stronger. Look away to fight it.
- It stays within 24 blocks of where it started and never takes blocks.

### The Iron Tyrant — `ncc:iron_tyrant`
A towering iron golem, 300 health. It always goes for the nearest player and hits for 4–11.
- **Ground slam:** every 9 s, when someone is close, a warning, then a shockwave: 5 damage, and it launches them.
- **Boulders:** every 7 s a dust ring marks a spot near a player further away, then a boulder lands there: 8 damage.
- **At half health:** it plates itself in iron: 14 armour and no knockback, slower but harder hits (up to about 14),
  and the slam does 7.

### The Purpur Sentinel — `ncc:purpur_sentinel`
A giant shulker, 140 health.
- **Bullet volleys:** every 6 s a homing shulker bullet goes to each of up to three players (levitation).
- **Teleport:** every 12 s it moves to one of four spots around its post, marked a second before.
- **Shell:** closed, it takes little damage. Hit it while it's open.
- **At half health:** 3 Purpur Turret shulkers.

### The Cinder Lord — `ncc:cinder_lord`
A big blaze, 140 health, in a ring of three fire shields.
- **Fire shields:** while any shield is up, it takes only a quarter of the damage. A shield breaks after 3 snowballs
  or 5 hits. Players get 16 snowballs at the start, topped up every 20 s.
- **Flame pillars:** every 8 s a ring warns under up to two players, then a pillar of fire: 6 damage, and they burn.
- **Fireball spirals:** with all shields down, eight fireballs in a turning spiral every 3 s.

### The Weeping Ghast — `ncc:weeping_ghast`
A huge ghast, 120 health, high above the arena (below the box's ceiling). Its fireballs break nothing.
- **Fireball rain:** every 7 s three spots near players are marked, then fire comes down on them.
- **Tears:** every 5 s its tears leave a slowing cloud on the ground below.
- **Little ghasts:** two every 25 s (up to four).
- **Hit its fireballs back:** each returned fireball does 20 damage. That's what really hurts it.

### The Dire Alpha — `ncc:dire_alpha`
A big wolf in wolf armour, 160 health, with its pack.
- **Pounce:** every 7 s it leaps at a player: 6 damage and bleeding (wither).
- **Pack:** it starts with 2 Pack Wolves and howls for 3 more every 18 s (up to 6).
- **Bleeding bites:** its bites wither.
- **Below a quarter:** the pack runs off. It fights on alone, faster and stronger, pouncing every 4 s.

## Katters Structures bosses

Designed by Katter for the Katters Structures datapack (Explorer's Eden), rebuilt in the mod. The datapack isn't
needed.

### Arachne, Mother of Spiders — `katters:arachne`
A giant spider, 160 health.
- **Attacks by health:** every 2 s, from 90 health down, one attack hits everyone within 15 blocks:

  | Health | Attacks |
  |---|---|
  | 90–60 | cobwebs or a wind burst |
  | 60–30 | a falling poison potion too |
  | Below 30 | strong poison or harming potions too |

- **Brood:** a player hitting her may bring a small spider, cave spider or silverfish.
- Mining fatigue for those near her. Projectiles near her fall to the ground.

### Raj Raksha — `katters:raj`
A pillager in diamond armour, 200 health, riding a floating elder guardian, 160 health.
- **Lightning arrows:** his arrows call lightning where they land.
- **Lightning rings:** every 10 s, half the time, a ring of lightning strikes around him. Players gliding near him
  get struck.
- **Minions:** hitting him may bring a minion (a guardian riding an invisible allay), or darkness on you.
- His guardian goes when he does.

### Rústa, Chieftain of the Ravaged Lands — `katters:rusta`
A giant illager, 200 health. Every 10–20 seconds it takes another form and keeps its health:

| Form | What it does |
|---|---|
| Vindicator | melee |
| Pillager | its arrows burst like wind charges |
| Witch | potion clouds around it, and it regenerates |
| Evoker | 5 large vexes, and fangs under the players |

### Tenkū Mizu Gami — `katters:tenku`
A giant turtle, 500 health, circled by 8 dolphins.
- **Dolphins:** it loses one for every 50 health lost. Each loss throws the nearest player back, at most once a
  minute.
- **Beams:** the dolphins shoot at players more than 13 blocks away. A beam leaves a cloud where it ends: nausea plus a
  conduit-looking blast (nothing broken), hunger, poison, slowness or wither.
- **Falling tridents and potions** land on players 12–40 blocks away.
- **Helpers:** a drowned riding a guardian, for players close by. When either is down, the other goes too.
- Out of water it hovers 14–20 blocks from the players. In water it swims.

### Magus Theron the Betrayer — `katters:theron`
An illusioner by his lightning rod, 200 health.
- **Waking:** he wakes when a player comes within 20 blocks.
- **Minion phases:** at 150, 100 and 50 health he flies up out of reach and sends 4–6 minions (pillagers,
  vindicators, an evoker). He comes back once they're beaten.
- **Last form:** killed, he returns as Theron, the Ravaged Betrayer, a ravager. The fight is won when that falls.
- He talks the whole time.
