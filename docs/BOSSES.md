# Bosses

Built-in boss fights for wave arenas and dungeons. Write one like a mob:

- **Wave arenas:** add it to the arena's **Bosses** list (the panel has a picker under it), or put it in a
  hand-written wave. With "Boss wave every … waves", each boss wave brings the boss on its own.
- **Dungeons:** use it in a boss marker, for example `/dungeon tool marker boss:ncc:frost_warden`.
- **Rogue runs:** add it to the run's bosses (picked at random for boss rooms; `w3` makes one more likely), or a boss
  marker in a rogue room: `/rogue tool marker boss:ncc:frost_warden`.
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

### The Heartwood Creaking — `ncc:heartwood_creaking`
A giant creaking, 120 health.
- **Only moves unseen:** like any creaking, it moves only while nobody looks at it.
- **Resin hearts:** three Resin Hearts stand 6–10 blocks around it. While any of them stands, it can't be hurt.
  Break the hearts (30 health each), then the creaking.
- **Root snares:** every 10 s resin grows around a player and slows them.
- **At half health:** once all hearts are broken, one grows back, once.

### The Abyssal Eye — `ncc:abyssal_eye`
An elder guardian floating over the arena, 160 health.
- **Laser:** every 7 s it locks on to a player for 2 seconds (a line of bubbles), then fires: 8 damage, unless a
  block is in the way. Take cover.
- **Gaze:** mining fatigue for everyone within 40 blocks.
- **Spikes:** whoever hits it close up takes 2 damage back.
- **At half health:** 3 Abyssal Spawn guardians.

### The Rotting Giant — `ncc:rotting_giant`
The giant zombie, 180 health, walked by the mod.
- **Plods on:** it walks slowly at the nearest player and swipes at anyone at its feet (6).
- **Stomp:** every 8 s, when someone is close, the ground warns, then a shockwave: 6 damage.
- **Thrown zombies:** every 10 s it throws a Zombie Spawn at a marked spot.
- **Grab:** every 15 s the nearest player within 6 blocks is lifted up and dropped.
- **Rest:** every 30 s it sinks to its knees for 4 seconds. Every hit then does double damage.

### The Arch-Evoker — `ncc:arch_evoker`
An evoker, 160 health.
- **Fang patterns:** every 5.5 s, in turn: a line of fangs at a player, two rings around itself, a spiral out from
  itself.
- **Vex storm:** every 20 s, 4 Storm Vexes (up to 6).
- **Warding totem at half health:** a floating totem (40 health) shields it completely until it's broken, and the
  fangs come every 3 s meanwhile.

### The Oxidized Engine — `ncc:oxidized_engine`
A towering copper golem, 180 health, walked by the mod.
- **Rusting:** every 30 s it oxidizes a stage (exposed, weathered, oxidized). Each stage it gets slower, but its hits
  (5, rising by 1.5 a stage) and slams hurt more.
- **Lightning rods:** three Lightning Rods stand around it (20 health each). Every 40 s lightning strikes a standing
  rod and polishes it fresh again, with some healing. Break the rods.
- **Slam:** every 9 s, when someone is close, a warning, then a shockwave.
- **Sparks:** every 12 s a ring of sparks, then a jolt for everyone within 4.5 blocks (5).

### The Ramlord — `ncc:ramlord`
A giant goat, 160 health.
- **Long ram:** every 10 s a line of dust marks it, then it charges along it: 6 damage, and players are thrown far.
- **Horn throw:** every 7 s a horn flies at a player: 6 damage.
- **Rockfall:** every 9 s dust marks a spot near a player further away, then a rock lands there: 7 damage.
- **Mountain leap:** every 14 s it jumps at a marked spot and lands in a shockwave: 6 damage.

### The Llama Matriarch — `ncc:llama_matriarch`
A big llama in a red blanket, 140 health, with 3 Caravan Llamas.
- **Spit volleys:** every 4 s she and each caravan llama spit at a player.
- **Stampede:** every 15 s a line is marked, then the caravan runs along it: 5 damage, and a knock.
- **Lead lash:** every 12 s the farthest player within 30 blocks is pulled towards her.
- She closes in when players keep away.

### The Panda Grandmaster — `ncc:panda_grandmaster`
A big aggressive panda, 160 health.
- **Roll:** every 9 s a line is marked, then it rolls through it: 6 damage, and a knock.
- **Sneeze:** every 11 s, when someone is close, everyone in front of it is blown away and slowed.
- **Bamboo:** every 12 s bamboo grows in a ring around a player for 7 seconds. Touching it hurts (2).
- **Snack:** every 25 s it sits down to eat for 4 seconds. Every hit then does double damage.
- **Below 30 %:** worried, and so faster and stronger. Rolls come every 6 s.

### The Trickster Fox — `ncc:trickster_fox`
A big snow fox, 120 health.
- **Copies:** every 18 s three copies of it appear. A copy vanishes when hit; the real one throws sparks.
- **Blink and bite:** every 5 s it blinks up to a player and bites (5).
- **Pounce:** every 11 s it jumps at a marked spot: 6 damage.
- **Pickpocket:** every 15 s it snatches what the nearest player holds. It comes back after 5 seconds, or when the
  fight ends, or when they next join if they left.

### The Glacier Bear — `ncc:glacier_bear`
A huge polar bear, 180 health, with 2 Glacier Cubs.
- **Enraged:** hurt a cub and she's enraged for 10 seconds: faster and stronger.
- **Ice slide:** every 10 s a line is marked, then she slides along it leaving snow: 6 damage, and a knock.
- **Roar:** every 12 s, when someone is close, a roar stuns everyone within 6 blocks (slowness IV and weakness for
  2 s).
- **Snowballs:** the cubs throw snowballs every 3 s.

### The Elder Sniffer — `ncc:elder_sniffer`
An ancient sniffer, 200 health.
- **Sniffs out the farthest:** it goes for whoever keeps farthest away, and knocks anyone it walks into (5).
- **Buried mines:** every 8 s three spots near players are marked, and they burst 3 seconds later (nothing broken).
- **Thornbush walls:** every 12 s a wall of berry bushes grows between it and its prey for 10 seconds.
- **Ancient seed turrets:** every 20 s a seed turret grows (up to two, 15 health each). It spits at the nearest
  player every 2 s (2) until it's broken.

### The Shellguard — `ncc:shellguard`
A giant armadillo, 140 health.
- **Curls up:** hit it three times within 3 seconds and it curls up for 4 seconds, taking no damage. A mace still gets
  through.
- **Rolled charge:** every 9 s a line is marked, then it rolls along it: 6 damage, and a knock.
- **Scute shrapnel:** every 7 s, when someone is close, a ring warns, then everyone within 5 blocks takes 3.
- **Burrow:** every 16 s it vanishes into the ground and comes up 2 seconds later under a marked player: 6 damage, and
  they're thrown up.

### Village bosses
The village bosses are made for an arena full of villagers and golems: **the village fights on their side**. When the
fight starts, the villagers, iron golems, copper golems and snow golems inside the fight box join in (without a box,
those within 32 blocks, but not iron golems a player built).
- Residents are never killed: a blow that would kill one knocks it out instead. It sits out the rest of the fight and
  takes no more damage, so the arena keeps its villagers.
- The village's iron golems hit at 40 % of their usual strength while they fight (vanilla's golem hits two-shot
  anyone).
- When the fight is over (won, lost or cleared) every resident calms down and is healed. Mobs that were already wave
  mobs fight on as before.

### The Village Elder — `ncc:village_elder`
An old cleric villager, 140 health.
- **Alarm bell:** at the start and every 30 s he rings the bell: the village joins (newcomers too) and is faster and
  stronger for 8 seconds.
- **Bodyguards:** while a golem stands within 4 blocks of him he takes half damage. Pull the golems away first.
- **Mending prayer:** every 12 s he prays over his most hurt golem for 3 seconds (a green beam), healing it. A
  player's hit breaks the prayer off.
- **Potions:** every 7 s a splash potion falls on a player: weakness, then slowness.
- **At half health:** two Village Guard iron golems join (60 health, hits of 3–9).

### The Master Smith — `ncc:master_smith`
The village armorer, a big villager, 160 health. He walks up and hits for 5.
- **Hammer slam:** every 8 s, when someone is close, a ring of anvil dust warns, then everyone within 6 blocks takes 6
  and is thrown back.
- **Anvils:** every 10 s anvils fall on three marked spots near players: 7 damage. They break nothing.
- **Golem forge:** every 20 s he starts a copper block next to him (30 health, glowing). Not broken within 8 seconds,
  it becomes a Forged Golem that joins the fight. Two at most.
- **Tempering:** every 25 s his golems (the village's and his own) get resistance for 6 seconds.

### The Grand Librarian — `ncc:grand_librarian`
A librarian floating 2 blocks up among three enchanted books, 120 health. He keeps 6–10 blocks away.
- **Books:** while any book circles him he takes only 30 % damage. Each book has 20 health and casts every 5 s:
  levitation, slowness, or a harming bolt (4). A block in the way stops the spell.
- **Hush:** every 13 s a ring marks a player's spot, then everyone in it gets weakness and blindness for 3 seconds.
- **Shelf walls:** every 18 s a bookshelf wall rises across the way to the nearest player, for 8 seconds.
- **At half health:** his broken books come back, once.

### The Harvest Keeper — `ncc:harvest_keeper`
The village farmer, a big villager, 150 health. He walks up and hits for 5.
- **Scarecrows:** three carved pumpkins stand around him (25 health each). While any stands, he mends.
- **Scythe:** every 7 s, when someone is close, sweeps warn in front of him, then everyone in front within 7 blocks
  takes 6.
- **Hay bales:** every 9 s hay falls on every player's spot after a warning: 4 damage, slowness, a knock.
- **Field helpers:** two snow golems to start, up to four (a new one every 20 s). Their snow melts again.
- **Supper:** every 30 s he sits down to eat for 3 seconds. He heals a little, but takes double damage while he
  eats.

### Pillager bosses
Made for a pillager arena. Their raiders never join a real raid.

### The Raid Captain — `ncc:raid_captain`
A big pillager with a crossbow and the ominous banner on his head, 140 health.
- **War banner:** a banner stands 5 blocks from him (40 health, glowing). While it stands, he and every raider within
  24 blocks of it are stronger.
- **Raid horn:** every 25 s he sounds the horn and three pillagers and a vindicator join (eight helpers at most).
- **Rockets:** every 9 s up to three players' spots are marked, and a rocket arcs to each: 6 damage within 2 blocks.
- **At 30 %:** his last raiders come: two vindicators and two vexes.

### The Headsman — `ncc:headsman`
A giant vindicator with an iron axe, 160 health. His hits do 6.
- **Shield breaker:** a blocked hit knocks the shield out of use for 5 seconds.
- **Axe spin:** every 8 s, when someone is close, a ring warns, then everyone within 4.5 blocks takes 6 and is
  thrown out.
- **Leap:** every 9 s he jumps at someone 6–16 blocks off, onto a marked spot: 7 within 3.5 blocks when he lands.
- **Execution:** every 15 s he marks the player with the lowest health (they glow). For 5 seconds he goes only for
  them, with a much stronger axe. The others break it by doing him 12 damage.
- **Below 30 %:** frenzy: faster, stronger, and he spins and leaps more often.

### The Siege Engineer — `ncc:siege_engineer`
A big pillager with a crossbow and a TNT block on his head, 130 health.
- **Ballista:** a dispenser 5 blocks from him (30 health, glowing) fires five arrows at the nearest player every 3 s
  until it's broken. At half health he builds a new one, once.
- **Mortar:** every 9 s TNT falls on two marked players' spots and goes off. It breaks nothing.
- **Caltrops:** every 12 s cobwebs appear around a player for 5 seconds.
- **Barricade:** when someone comes within 4 blocks he fences himself in for 6 seconds (every 15 s at most).

### The Beastmaster — `ncc:beastmaster`
A pillager (120 health) riding his ravager, Gorehorn (100 health, hits of 6). **Both must fall.**
- **Charge:** every 9 s a line is marked, then the ravager charges along it: 7 damage and a hard knock.
- **Roar:** every 12 s, when someone is close, a ring warns, then everyone within 5 blocks takes 4, is thrown back,
  and is slowed and weakened for 2 seconds.
- **The Beastmaster** shoots his crossbow from the saddle.
- **Gorehorn falls first:** he fights on foot, faster and stronger. Two War Hounds join at once, then two more
  every 15 s (four at most), and every 8 s he snares a player (a cobweb at their feet, slowness).
- **He falls first:** Gorehorn goes berserk: faster, and it charges every 6 s.

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
