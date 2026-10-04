package dev.dummycraft;

import java.util.*;

/**
 * All DummyCraft rules live here. No Minecraft imports, so this file can be
 * compiled and unit-tested on its own. Chunks are addressed as "x,z" strings
 * (chunk coordinates, overworld only).
 */
public class Game {
    public static final double START_GOLD = 100, START_OIL = 10, START_TROOPS = 20, RECRUIT_COST = 5;
    public static final int MIN_ATTACK = 5;
    public static final int MAP_RADIUS = 8;
    public static final int MAP_SURFACE_Y = 64;
    public static final int SEA_TRAVEL_RANGE = 12;
    public static final double CAPITAL_GOLD = 0.5;   // flat gold/s from the capital, whatever its terrain
    public static final double UPKEEP = 0.005;       // gold/s per troop
    public static final double TURN_ECONOMY_SCALE = 20.0; // convert prototype per-second yields into useful per-round yields

    /** Chat colours (names of net.minecraft.ChatFormatting constants), handed out in order. */
    static final String[] COLORS = {"RED", "BLUE", "GREEN", "YELLOW", "LIGHT_PURPLE", "AQUA",
            "GOLD", "DARK_GREEN", "DARK_AQUA", "DARK_PURPLE", "DARK_RED", "WHITE"};

    /** Per-second yield per chunk: {gold, manpower, oil}. */
    static final double[][] YIELD = {{0.05, 0.08, 0.0}, {0.10, 0.0, 0.12}, {0.10, 0.03, 0.02}};
    static final String[] TYPE_NAMES = {"farm", "oil field", "nuclear site"};

    public Map<String, Nation> nations = new LinkedHashMap<>();
    public Map<String, String> owner = new HashMap<>();   // "x,z" -> nation id
    public List<Op> ops = new ArrayList<>();               // active occupations
    /** Unit stacks stored by world chunk and unit id. */
    public Map<String, Map<String, Integer>> garrisons = new HashMap<>();
    public Map<String, Integer> terrain = new HashMap<>();
    public Map<String, Integer> resourceQuality = new HashMap<>();
    /** Persistent ids for visible vanilla-client unit mannequins. */
    public Map<String, String> markerIds = new HashMap<>();
    public boolean mapGenerated;
    public long mapSeed;
    public int mapCenterX, mapCenterZ;
    public Set<String> mapChunks = new HashSet<>();
    public Set<String> waterChunks = new HashSet<>();
    public List<String> scenarioCountries = new ArrayList<>();
    /** Optional turn-based mode; order and round survive a server restart. */
    public boolean turnBased;
    public List<String> turnOrder = new ArrayList<>();
    public int turnIndex, round;

    public transient Map<UUID, String> invites = new HashMap<>();
    public transient Events events = new Events() {
        public void toNation(Nation n, String msg) {}
        public void toAll(String msg) {}
    };

    public static class Nation {
        public String id, name, color, borderColor, capital;
        /** Owned cities; capital is always included. */
        public Set<String> cities = new LinkedHashSet<>();
        public UUID leader;
        public boolean computerControlled;
        public Set<UUID> members = new LinkedHashSet<>();
        public Set<String> enemies = new LinkedHashSet<>();
        public Set<String> peaceOffers = new LinkedHashSet<>(); // nations that offered peace to us
        public double gold, oil, troops;                         // troops = reserve (not deployed)
        public Map<String, Integer> upgrades = new LinkedHashMap<>();
    }

    public static class Op {
        public String chunk, attacker, sourceChunk, unitType;
        public double troops, progress;
    }

    public interface Events {
        void toNation(Nation n, String msg);
        void toAll(String msg);
    }

    public record R(boolean ok, String msg) {}
    static R ok(String m) { return new R(true, m); }
    static R err(String m) { return new R(false, m); }

    // ---------------------------------------------------------------- helpers

    public static String key(int x, int z) { return x + "," + z; }
    public boolean isWater(int x, int z) { return waterChunks.contains(key(x, z)); }
    public boolean isBoard(int x, int z) { return mapChunks.contains(key(x, z)); }
    public static int cx(String k) { return Integer.parseInt(k.substring(0, k.indexOf(','))); }
    public static int cz(String k) { return Integer.parseInt(k.substring(k.indexOf(',') + 1)); }

    /** Each chunk deterministically has a resource type, like fixed resource points in Dummynation. */
    public static int type(String chunk) {
        return Math.floorMod(cx(chunk) * 73856093 ^ cz(chunk) * 19349663, 3);
    }
    public static String typeName(String chunk) { return TYPE_NAMES[type(chunk)]; }
    public int terrainType(String chunk) { return terrain.getOrDefault(chunk, type(chunk)); }
    public int resourceQuality(String chunk) { return resourceQuality.getOrDefault(chunk, 2); }
    public double resourceMultiplier(String chunk) { return switch (resourceQuality(chunk)) { case 1 -> 0.6; case 3 -> 1.4; default -> 1.0; }; }
    public String terrainName(String chunk) {
        String rank = switch (resourceQuality(chunk)) { case 1 -> "poor"; case 3 -> "rich"; default -> "standard"; };
        return TYPE_NAMES[terrainType(chunk)] + " (" + rank + ")";
    }

    public Nation nationOf(UUID p) {
        for (Nation n : nations.values()) if (n.members.contains(p)) return n;
        return null;
    }
    public Nation byName(String s) {
        Nation direct = nations.get(s.toLowerCase(Locale.ROOT));
        if (direct != null) return direct;
        String normalized = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        for (Nation n : nations.values()) {
            String normalizedId = n.id.replaceAll("[^a-z0-9]+", "");
            String normalizedName = n.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
            if (normalizedId.equals(normalized) || normalizedName.equals(normalized)) return n;
        }
        return null;
    }


    public Nation ownerOf(String chunk) {
        String id = owner.get(chunk);
        return id == null ? null : nations.get(id);
    }
    public Nation at(int x, int z) { return ownerOf(key(x, z)); }
    public Op opAt(int x, int z) {
        String k = key(x, z);
        for (Op o : ops) if (o.chunk.equals(k)) return o;
        return null;
    }
    public int chunksOf(Nation n) {
        int c = 0;
        for (String id : owner.values()) if (id.equals(n.id)) c++;
        return c;
    }
    public double deployed(Nation n) {
        double d = 0;
        for (Op o : ops) if (o.attacker.equals(n.id)) d += o.troops;
        return d;
    }
    public double score(Nation n) { return chunksOf(n) * 10 + unitCount(n) + deployed(n) + n.gold / 10; }
    private boolean hasTurn(Nation n) { return !turnBased || activeNation() == n; }

    public Map<String, Integer> unitsAt(String chunk) {
        return garrisons.computeIfAbsent(chunk, k -> new LinkedHashMap<>());
    }
    public int unitCount(Nation n) {
        int total = 0;
        for (Map.Entry<String, Map<String, Integer>> e : garrisons.entrySet())
            if (n.id.equals(owner.get(e.getKey()))) for (int count : e.getValue().values()) total += count;
        return total;
    }
    public int unitCount(Nation n, UnitType type) {
        int total = 0;
        for (Map.Entry<String, Map<String, Integer>> e : garrisons.entrySet()) if (n.id.equals(owner.get(e.getKey())))
            total += e.getValue().getOrDefault(type.id(), 0);
        return total;
    }
    public double power(Nation n, String domain, boolean defense) {
        double total = 0;
        for (Map.Entry<String, Map<String, Integer>> e : garrisons.entrySet()) if (n.id.equals(owner.get(e.getKey()))) {
            for (Map.Entry<String, Integer> stack : e.getValue().entrySet()) {
                UnitType u = UnitType.parse(stack.getKey());
                if (u == null) continue;
                int stat = switch (domain) {
                    case "air" -> defense ? u.airDefense : u.airAttack;
                    case "water" -> defense ? u.waterDefense : u.waterAttack;
                    default -> defense ? u.groundDefense : u.groundAttack;
                };
                total += stat * stack.getValue();
            }
        }
        int level = n.upgrades.getOrDefault(domain + (defense ? "_defense" : "_attack"), 0);
        return total * (1 + 0.15 * level);
    }

    /** "Do not spread too thin": holding far more land than your army can cover cuts income. */
    public double efficiency(Nation n, int chunks) {
        double over = Math.max(0, chunks - (8 + unitCount(n) / 4.0));
        return Math.max(0.25, 1 - 0.03 * over);
    }

    boolean adjacentToOwned(Nation n, int x, int z) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] v : d) if (n.id.equals(owner.get(key(x + v[0], z + v[1])))) return true;
        return false;
    }

    /** Wilderness is open; your own land is open; enemy land is open to raiders (you are at war). */
    public boolean canBuild(UUID p, int x, int z) {
        if (waterChunks.contains(key(x, z))) return false;
        Nation o = at(x, z);
        if (o == null) return true;
        Nation mine = nationOf(p);
        if (mine == o) return true;
        return mine != null && mine.enemies.contains(o.id);
    }

    // ---------------------------------------------------------------- commands

    public R create(UUID p, String name, int x, int z) {
        if (turnBased) return err("A match is already running; new countries cannot join this save.");
        if (mapGenerated) return err("This match uses the generated country map. Choose a free country with /start as <name>.");
        if (nationOf(p) != null) return err("You are already in a nation.");
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return err("Name must be 3-16 letters, digits or underscores.");
        String id = name.toLowerCase(Locale.ROOT);
        if (nations.containsKey(id)) return err("That name is taken.");
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (owner.containsKey(key(x + dx, z + dz))) return err("Too close to claimed land (keep 2 chunks clear).");
        Nation n = new Nation();
        n.id = id; n.name = name; n.leader = p; n.members.add(p);
        n.gold = START_GOLD; n.oil = START_OIL; n.troops = 0;
        n.capital = key(x, z);
        n.cities.add(n.capital);
        Set<String> used = new HashSet<>();
        for (Nation o : nations.values()) used.add(o.color);
        n.color = COLORS[nations.size() % COLORS.length];
        for (String c : COLORS) if (!used.contains(c)) { n.color = c; break; }
        n.borderColor = n.color;
        unitsAt(n.capital).put(UnitType.INFANTRY.id(), (int) START_TROOPS);
        nations.put(id, n);
        owner.put(n.capital, id);
        events.toAll("The nation of " + name + " has been founded!");
        return ok("You founded " + name + ". This chunk (" + terrainName(n.capital) + ") is your capital.");
    }

    public R setBorderColor(Nation n, String requested) {
        String value = requested.toUpperCase(Locale.ROOT);
        for (String color : COLORS) if (color.equals(value)) {
            n.borderColor = value;
            return ok("Border colour set to " + value.toLowerCase(Locale.ROOT) + ".");
        }
        return err("Choose a colour: red, blue, green, yellow, light_purple, aqua, gold, dark_green, dark_aqua, dark_purple, dark_red or white.");
    }

    public R rename(Nation n, String requested) {
        String value = requested.trim().replaceAll("\\s+", " ");
        if (value.length() < 3 || value.length() > 24 || !value.matches("[A-Za-z0-9_ ]+"))
            return err("Nation names must be 3-24 letters, numbers, spaces or underscores.");
        for (Nation other : nations.values())
            if (other != n && other.name.equalsIgnoreCase(value)) return err("That nation name is already taken.");
        String old = n.name;
        n.name = value;
        events.toAll(old + " is now called " + value + ".");
        return ok("Your nation is now called " + value + ".");
    }

    public int citiesOf(Nation n) { return n.cities == null ? 0 : n.cities.size(); }


    public R recruit(Nation n, int amount) {
        if (amount < 1 || amount > 1000) return err("Recruit between 1 and 1000 troops.");
        return buy(n, UnitType.INFANTRY, amount, n.capital);
    }

    public R buy(Nation n, UnitType type, int amount, String atChunk) {
        if (!hasTurn(n)) return err("Wait for your nation's turn before buying units.");
        if (type == null) return err("Unknown unit type.");
        if (amount < 1 || amount > 100) return err("Buy between 1 and 100 units at a time.");
        if (!n.id.equals(owner.get(atChunk))) return err("You can only station units on your own land.");
        double goldCost = (double) type.gold * amount;
        double oilCost = (double) type.oil * amount;
        if (n.gold < goldCost || n.oil < oilCost)
            return err("Cost: " + (int) goldCost + " gold and " + (int) oilCost + " oil. You have " + (int) n.gold + " gold and " + (int) n.oil + " oil.");
        n.gold -= goldCost; n.oil -= oilCost;
        unitsAt(atChunk).merge(type.id(), amount, Integer::sum);
        return ok("Bought " + amount + " " + type.title + " for " + (int) goldCost + " gold and " + (int) oilCost + " oil.");
    }

    public R buyUpgrade(Nation n, String id) {
        if (!hasTurn(n)) return err("Wait for your nation's turn before buying upgrades.");
        if (!Set.of("ground_attack", "ground_defense", "air_attack", "air_defense", "water_attack", "water_defense", "income").contains(id))
            return err("Unknown upgrade.");
        int level = n.upgrades.getOrDefault(id, 0);
        if (level >= 5) return err("That upgrade is already at maximum level 5.");
        int goldCost = 30 + level * 20, oilCost = 3 + level * 2;
        if (n.gold < goldCost || n.oil < oilCost) return err("Upgrade cost: " + goldCost + " gold and " + oilCost + " oil.");
        n.gold -= goldCost; n.oil -= oilCost;
        n.upgrades.put(id, level + 1);
        return ok(id.replace('_', ' ') + " upgraded to level " + (level + 1) + ".");
    }

    public double[] incomePerRound(Nation n) {
        double[] result = new double[3];
        int chunks = chunksOf(n);
        for (Map.Entry<String, String> e : owner.entrySet()) if (n.id.equals(e.getValue())) {
            boolean capital = e.getKey().equals(n.capital);
            boolean city = n.cities != null && n.cities.contains(e.getKey());
            double siteMultiplier = capital ? 3.0 : city ? 1.5 : 1.0;
            double factor = TURN_ECONOMY_SCALE * siteMultiplier * efficiency(n, chunks) * resourceMultiplier(e.getKey());
            double[] y = YIELD[terrainType(e.getKey())];
            result[0] += y[0] * factor;
            result[1] += y[1] * factor;
            result[2] += y[2] * factor;
            if (capital) result[0] += CAPITAL_GOLD * TURN_ECONOMY_SCALE;
        }
        double economyUpgrade = 1 + 0.15 * n.upgrades.getOrDefault("income", 0);
        result[0] *= economyUpgrade; result[1] *= economyUpgrade; result[2] *= economyUpgrade;
        return result;
    }

    public R moveUnits(Nation n, UnitType type, int amount, String from, String to) {
        if (!hasTurn(n)) return err("Wait for your nation's turn before moving units.");
        if (type == null) return err("Unknown unit type.");
        if (amount < 1 || !n.id.equals(owner.get(from)) || !n.id.equals(owner.get(to))) return err("Choose an amount and two chunks you own.");
        int distance = Math.abs(cx(from) - cx(to)) + Math.abs(cz(from) - cz(to));
        if (type.domain.equals("ground") && distance != 1)
            return err("Ground troops move only to neighboring land; they cannot cross sea.");
        if (!type.domain.equals("ground") && (distance < 1 || distance > SEA_TRAVEL_RANGE))
            return err("Air and ship forces can redeploy up to " + SEA_TRAVEL_RANGE + " chunks.");
        Map<String, Integer> source = unitsAt(from);
        int have = source.getOrDefault(type.id(), 0);
        if (amount > have) return err("That chunk has only " + have + " " + type.title + ".");
        stackMove(source, unitsAt(to), type, amount);
        return ok("Moved " + amount + " " + type.title + " to " + to.replace(",", ", ") + ".");
    }

    /** Route a ground stack over friendly connected land; crouch-click can leave units along the route. */
    public R moveUnitsAlongLine(Nation n, UnitType type, int amount, String from, int targetX, int targetZ, boolean distribute) {
        if (!hasTurn(n)) return err("Wait for your nation's turn before moving units.");
        if (type == null) return err("Unknown unit type.");
        if (!n.id.equals(owner.get(from))) return err("The selected troops are no longer at their starting tile.");
        String target = key(targetX, targetZ);
        if (!n.id.equals(owner.get(target))) return err("Right-click friendly land to move; use the menu to attack.");
        int distance = Math.abs(cx(from) - targetX) + Math.abs(cz(from) - targetZ);
        if (distance == 0) return err("Choose a different destination.");
        if (!type.domain.equals("ground")) return moveUnits(n, type, amount, from, target);
        Map<String, Integer> original = unitsAt(from);
        int have = original.getOrDefault(type.id(), 0);
        if (amount < 1 || amount > have) return err("That tile has only " + have + " " + type.title + ".");
        List<String> path = new ArrayList<>();
        int x = cx(from), z = cz(from);
        int dx = Math.abs(targetX - x), dz = Math.abs(targetZ - z);
        int stepX = Integer.signum(targetX - x), stepZ = Integer.signum(targetZ - z);
        int error = dx - dz;
        while (x != targetX || z != targetZ) {
            int twiceError = error * 2;
            if (twiceError > -dz) { error -= dz; x += stepX; path.add(key(x, z)); }
            if (twiceError < dx) { error += dx; z += stepZ; path.add(key(x, z)); }
        }
        for (String tile : path) {
            if (!n.id.equals(owner.get(tile)) || waterChunks.contains(tile))
                return err("Ground forces need a continuous friendly land route; sea cannot be crossed.");
        }
        if (distribute && amount > 1) {
            original.compute(type.id(), (k, v) -> v == amount ? null : v - amount);
            int[] shares = new int[path.size()];
            for (int troop = 0; troop < amount; troop++) {
                int routeIndex = (int) (((2L * troop + 1) * path.size()) / (2L * amount));
                shares[Math.min(path.size() - 1, routeIndex)]++;
            }
            for (int i = 0; i < path.size(); i++)
                if (shares[i] > 0) unitsAt(path.get(i)).merge(type.id(), shares[i], Integer::sum);
        } else {
            stackMove(original, unitsAt(target), type, amount);
        }
        return ok(distribute ? "Moved " + amount + " " + type.title + " and spread them evenly along the route." : "Moved " + amount + " " + type.title + " along friendly land to " + target + ".");
    }



    public R attackUnits(Nation n, UnitType type, int amount, String from, int targetX, int targetZ) {
        if (!hasTurn(n)) return err("Wait for your nation's turn before attacking.");
        if (type == null) return err("Unknown unit type.");
        String target = key(targetX, targetZ);
        Nation defender = ownerOf(target);
        if (amount < 1) return err("Choose at least one unit.");
        if (!n.id.equals(owner.get(from))) return err("Stand on one of your own chunks to launch the attack.");
        if (defender == n) return err("You already own that chunk.");
        if (defender != null && !n.enemies.contains(defender.id)) return err("Declare war on " + defender.name + " first.");
        if (mapGenerated && !isBoard(targetX, targetZ)) return err("That target is outside the generated game map.");
        if (waterChunks.contains(target)) return err("That target is sea. Sea can be crossed by air or ships, not captured.");
        int distance = Math.abs(cx(from) - targetX) + Math.abs(cz(from) - targetZ);
        if (type.domain.equals("ground") && distance != 1)
            return err("Ground forces can attack neighboring land only; sea blocks them.");
        if (!type.domain.equals("ground") && (distance < 1 || distance > SEA_TRAVEL_RANGE))
            return err("Air and ship forces can attack across up to " + SEA_TRAVEL_RANGE + " chunks.");
        if (opAt(targetX, targetZ) != null) return err("That chunk already has an active attack.");
        Map<String, Integer> source = unitsAt(from);
        if (source.getOrDefault(type.id(), 0) < amount) return err("That chunk does not have enough " + type.title + ".");
        source.compute(type.id(), (k, v) -> v == amount ? null : v - amount);
        Op op = new Op(); op.chunk = target; op.sourceChunk = from; op.attacker = n.id; op.unitType = type.id(); op.troops = amount;
        ops.add(op);
        if (defender != null) events.toNation(defender, n.name + " sent " + amount + " " + type.title + " against " + target.replace(",", ", ") + ".");
        return ok("Attack sent: " + amount + " " + type.title + " from " + from.replace(",", ", ") + " to " + target.replace(",", ", ") + ".");
    }

    private static void stackMove(Map<String, Integer> source, Map<String, Integer> target, UnitType type, int amount) {
        source.compute(type.id(), (k, v) -> v == amount ? null : v - amount);
        target.merge(type.id(), amount, Integer::sum);
    }

    /** Begin a match with a shuffled nation initiative order. */
    public R startTurns() {
        List<String> players = nations.values().stream().filter(n -> !n.members.isEmpty()).map(n -> n.id).toList();
        if (players.size() < 2) return err("At least two players must choose a country before the match starts.");
        turnOrder = new ArrayList<>(players);
        Collections.shuffle(turnOrder);
        turnBased = true;
        turnIndex = 0;
        round = 1;
        return ok("Turn-based match started. Round 1: " + activeNation().name + " goes first.");
    }

    /** Build a small randomized country map around the player who starts the lobby. */
    public R generateMap(int centerX, int centerZ) {
        if (mapGenerated) return err("The country map is already ready. Choose a country with /start as <name>.");
        if (!nations.isEmpty()) return err("A nation game already exists in this save. Use a fresh world for generated country selection.");

        mapSeed = new Random().nextLong();
        Random random = new Random(mapSeed);
        mapCenterX = centerX;
        mapCenterZ = centerZ;
        String[] names = {"Northland", "Ironvale", "Ambercoast", "Greenreach", "Bluehaven", "Sunspire"};
        int[][] candidates = {{-4,-2},{0,-5},{4,-2},{-4,2},{0,5},{4,2}};
        List<int[]> centers = new ArrayList<>(Arrays.asList(candidates));
        Collections.shuffle(centers, random);
        List<String> countryNames = new ArrayList<>(Arrays.asList(names));
        Collections.shuffle(countryNames, random);
        List<Nation> mapNations = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String name = countryNames.get(i), id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
            int[] offset = centers.get(i);
            int cx = centerX + offset[0], cz = centerZ + offset[1];
            Nation nation = new Nation();
            nation.id = id; nation.name = name; nation.color = COLORS[i % COLORS.length];
            nation.borderColor = nation.color; nation.capital = key(cx, cz); nation.computerControlled = true;
            nation.gold = START_GOLD; nation.oil = START_OIL;
            nations.put(id, nation); scenarioCountries.add(id); mapNations.add(nation);
        }
        // One broad, uneven continent. Country regions meet along shared land borders.
        double phase = random.nextDouble() * Math.PI * 2.0;
        for (int dx = -MAP_RADIUS; dx <= MAP_RADIUS; dx++) for (int dz = -MAP_RADIUS; dz <= MAP_RADIUS; dz++) {
            String tile = key(centerX + dx, centerZ + dz);
            mapChunks.add(tile); waterChunks.add(tile);
            double angle = Math.atan2(dz, dx);
            double coastRadius = 6.5 + 0.75 * Math.sin(angle * 3 + phase)
                    + 0.4 * Math.sin(angle * 5 - phase * 0.7) + 0.25 * Math.sin(angle * 8 + phase * 0.5);
            if (Math.sqrt(dx * dx + dz * dz) > coastRadius) continue;
            Nation nearest = null;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < mapNations.size(); i++) {
                Nation candidate = mapNations.get(i);
                int[] offset = centers.get(i);
                double ddx = dx - offset[0], ddz = dz - offset[1];
                double distance = ddx * ddx + ddz * ddz;
                if (distance < best) { best = distance; nearest = candidate; }
            }
            owner.put(tile, nearest.id);
            waterChunks.remove(tile);
            terrain.put(tile, random.nextInt(TYPE_NAMES.length));
            resourceQuality.put(tile, 1 + random.nextInt(3));
        }
        for (Nation nation : mapNations) {
            unitsAt(nation.capital).put(UnitType.INFANTRY.id(), (int) START_TROOPS);
            nation.cities.add(nation.capital);
            List<String> land = new ArrayList<>();
            for (Map.Entry<String, String> entry : owner.entrySet()) if (nation.id.equals(entry.getValue())) land.add(entry.getKey());
            Collections.shuffle(land, random);
            int cityCount = 1 + random.nextInt(3);
            for (String tile : land) {
                if (nation.cities.size() >= cityCount) break;
                if (tile.equals(nation.capital)) continue;
                boolean spaced = true;
                for (String city : nation.cities)
                    if (Math.abs(cx(city) - cx(tile)) + Math.abs(cz(city) - cz(tile)) < 2) { spaced = false; break; }
                if (spaced) nation.cities.add(tile);
            }
        }
        mapGenerated = true;
        events.toAll("A randomized connected six-country map is ready. Choose with /start as <name> or /start random.");
        return ok("Generated a randomized connected map with six countries: " + String.join(", ", countryNames) + ". Choose with /start as <name> or /start random.");
    }

    public List<String> availableCountries() {
        List<String> names = new ArrayList<>();
        for (String id : scenarioCountries) {
            Nation n = nations.get(id);
            if (n != null && n.computerControlled) names.add(n.name);
        }
        return names;
    }

    public R chooseCountry(UUID player, String requested) {
        if (!mapGenerated) return err("Generate the country map first with /start.");
        if (nationOf(player) != null) return err("You have already chosen a country.");
        Nation selected = null;
        if (requested.equalsIgnoreCase("random")) {
            List<String> available = availableCountries();
            if (!available.isEmpty()) selected = byName(available.get(new Random().nextInt(available.size())));
        } else selected = byName(requested);
        if (selected == null || !selected.computerControlled) return err("That country is not available. Choose one of: " + String.join(", ", availableCountries()) + ".");
        selected.computerControlled = false; selected.leader = player; selected.members.add(player);
        events.toNation(selected, "You joined " + selected.name + ". Your starter army and economy are ready.");
        if (turnBased) {
            turnOrder.add(selected.id);
            events.toAll(selected.name + " joined the match. Its turn was added to the end of the current order.");
            return ok("You chose " + selected.name + ". Its turn has been added to the current order.");
        }
        long players = nations.values().stream().filter(n -> !n.members.isEmpty()).count();
        if (players >= 2 && !turnBased) {
            R started = startTurns();
            if (started.ok()) events.toAll(started.msg());
        }
        return ok("You chose " + selected.name + ". Your capital is chunk " + selected.capital.replace(",", ", ") + ".");
    }

    public Nation activeNation() {
        if (!turnBased || turnOrder.isEmpty()) return null;
        turnIndex = Math.floorMod(turnIndex, turnOrder.size());
        return nations.get(turnOrder.get(turnIndex));
    }

    /** Finish a nation's turn. Once every nation has moved, award one round of income. */
    public R endTurn(UUID player) {
        Nation current = activeNation();
        Nation mine = nationOf(player);
        if (current == null) return err("No turn-based match is running. Use /start random.");
        if (mine == null || mine != current) return err("It is not your nation's turn.");
        turnIndex++;
        if (turnIndex >= turnOrder.size()) {
            turnIndex = 0;
            round++;
            roundIncomeAndBattles();
        }
        Nation next = activeNation();
        events.toAll("Round " + round + " — " + next.name + " is up.");
        return ok("Turn ended. " + next.name + " moves next.");
    }

    private void roundIncomeAndBattles() {
        Map<String, Integer> counts = new HashMap<>();
        for (String id : owner.values()) counts.merge(id, 1, Integer::sum);
        for (Nation n : nations.values()) {
            double[] income = incomePerRound(n);
            n.gold += income[0] - (unitCount(n) + deployed(n)) * UPKEEP;
            n.oil += income[2];
            n.troops += income[1];
            int newInfantry = (int) n.troops;
            n.troops -= newInfantry;
            if (newInfantry > 0) unitsAt(n.capital).merge(UnitType.INFANTRY.id(), newInfantry, Integer::sum);
            if (n.gold < 0) { n.gold = 0; n.troops *= 0.995; }
        }
        for (Op op : new ArrayList<>(ops)) step(op, counts);
    }

    public R attack(Nation n, int x, int z, int troops) {
        if (troops < MIN_ATTACK) return err("Send at least " + MIN_ATTACK + " troops.");
        String target = key(x, z);
        String source = null;
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            String candidate = key(x + d[0], z + d[1]);
            if (n.id.equals(owner.get(candidate)) && unitsAt(candidate).getOrDefault(UnitType.INFANTRY.id(), 0) >= troops) { source = candidate; break; }
        }
        if (source == null) return err("Stand on a chunk next to the target with enough infantry.");
        return attackUnits(n, UnitType.INFANTRY, troops, source, x, z);
    }

    public R retreat(Nation n, int x, int z) {
        if (!hasTurn(n)) return err("Wait for your nation's turn before retreating.");
        Op op = opAt(x, z);
        if (op == null || !op.attacker.equals(n.id)) return err("You have no occupation in this chunk.");
        ops.remove(op);
        if (op.unitType == null) n.troops += Math.max(0, op.troops);
        else if (op.sourceChunk != null) unitsAt(op.sourceChunk).merge(op.unitType, (int)Math.ceil(Math.max(0, op.troops)), Integer::sum);
        return ok("Troops withdrawn.");
    }

    public R war(Nation a, Nation b) {
        if (!hasTurn(a)) return err("Wait for your nation's turn before declaring war.");
        if (a == b) return err("You cannot declare war on yourself.");
        if (a.enemies.contains(b.id)) return err("You are already at war with " + b.name + ".");
        a.enemies.add(b.id); b.enemies.add(a.id);
        a.peaceOffers.remove(b.id); b.peaceOffers.remove(a.id);
        events.toAll(a.name + " declared war on " + b.name + "!");
        return ok("War declared on " + b.name + ".");
    }

    public R peace(Nation a, Nation b) {
        if (!hasTurn(a)) return err("Wait for your nation's turn before negotiating peace.");
        if (!a.enemies.contains(b.id)) return err("You are not at war with " + b.name + ".");
        if (a.peaceOffers.contains(b.id)) {
            a.enemies.remove(b.id); b.enemies.remove(a.id);
            a.peaceOffers.remove(b.id); b.peaceOffers.remove(a.id);
            for (Iterator<Op> it = ops.iterator(); it.hasNext(); ) {
                Op o = it.next();
                Nation t = ownerOf(o.chunk);
                boolean ab = o.attacker.equals(a.id) && t == b, ba = o.attacker.equals(b.id) && t == a;
                if (ab || ba) {
                    if (o.unitType == null) nations.get(o.attacker).troops += Math.max(0, o.troops);
                    else if (o.sourceChunk != null) unitsAt(o.sourceChunk).merge(o.unitType, (int)Math.ceil(Math.max(0, o.troops)), Integer::sum);
                    it.remove();
                }
            }
            events.toAll(a.name + " and " + b.name + " made peace.");
            return ok("Peace with " + b.name + ".");
        }
        b.peaceOffers.add(a.id);
        events.toNation(b, a.name + " offers peace. Accept with /nation peace " + a.name);
        return ok("Peace offer sent to " + b.name + ".");
    }

    public R invite(Nation n, UUID target) {
        if (!hasTurn(n)) return err("Wait for your nation's turn before inviting players.");
        if (nationOf(target) != null) return err("That player is already in a nation.");
        invites.put(target, n.id);
        return ok("Invitation sent.");
    }

    public R join(UUID p, Nation n) {
        if (!hasTurn(n)) return err("Wait for that nation's turn before joining.");
        if (nationOf(p) != null) return err("You are already in a nation.");
        if (!n.id.equals(invites.get(p))) return err("You have no invitation from " + n.name + ".");
        invites.remove(p);
        n.members.add(p);
        events.toNation(n, "A new member joined the nation.");
        return ok("You joined " + n.name + ".");
    }

    public R leave(UUID p) {
        Nation n = nationOf(p);
        if (n == null) return err("You are not in a nation.");
        if (!hasTurn(n)) return err("Wait for your nation's turn before leaving.");
        if (n.leader.equals(p)) return err("Leaders cannot leave; use /nation disband.");
        n.members.remove(p);
        return ok("You left " + n.name + ".");
    }

    public R disband(UUID p) {
        Nation n = nationOf(p);
        if (n == null) return err("You are not in a nation.");
        if (!hasTurn(n)) return err("Wait for your nation's turn before disbanding.");
        if (!n.leader.equals(p)) return err("Only the leader can disband the nation.");
        events.toAll("The nation of " + n.name + " has been dissolved.");
        removeNation(n);
        return ok("Nation dissolved.");
    }

    void removeNation(Nation n) {
        List<String> lostChunks = new ArrayList<>();
        for (Map.Entry<String, String> e : owner.entrySet()) if (e.getValue().equals(n.id)) lostChunks.add(e.getKey());
        owner.values().removeIf(id -> id.equals(n.id));
        for (String chunk : lostChunks) {
            garrisons.remove(chunk);
            markerIds.keySet().removeIf(k -> k.startsWith(chunk + "|"));
        }
        ops.removeIf(o -> o.attacker.equals(n.id));
        int removedAt = turnOrder.indexOf(n.id);
        if (removedAt >= 0) {
            turnOrder.remove(removedAt);
            if (removedAt < turnIndex) turnIndex--;
            if (!turnOrder.isEmpty() && turnIndex >= turnOrder.size()) turnIndex = 0;
        }
        for (Nation o : nations.values()) { o.enemies.remove(n.id); o.peaceOffers.remove(n.id); }
        nations.remove(n.id);
        scenarioCountries.remove(n.id);
    }

    // ---------------------------------------------------------------- simulation (call once per second)

    public void tick() {
        if (turnBased) return;
        Map<String, Integer> counts = new HashMap<>();
        for (String id : owner.values()) counts.merge(id, 1, Integer::sum);

        // economy: resources are tied to individual chunks, capital counts triple
        Map<String, double[]> income = new HashMap<>();
        for (Map.Entry<String, String> e : owner.entrySet()) {
            Nation n = nations.get(e.getValue());
            if (n == null) continue;
            double[] y = YIELD[terrainType(e.getKey())];
            boolean cap = e.getKey().equals(n.capital);
            double cityMultiplier = n.cities != null && n.cities.contains(e.getKey()) ? 1.5 : 1.0;
            double m = (cap ? 3 : cityMultiplier) * efficiency(n, counts.get(n.id)) * resourceMultiplier(e.getKey())
                    * (1 + 0.15 * n.upgrades.getOrDefault("income", 0));
            double[] a = income.computeIfAbsent(n.id, k -> new double[3]);
            a[0] += y[0] * m + (cap ? CAPITAL_GOLD : 0);
            a[1] += y[1] * m;
            a[2] += y[2] * m;
        }
        for (Nation n : nations.values()) {
            double[] a = income.getOrDefault(n.id, new double[3]);
            n.gold += a[0] - (unitCount(n) + deployed(n)) * UPKEEP;
            n.oil += a[2];
            n.troops += a[1];
            int newInfantry = (int) n.troops;
            n.troops -= newInfantry;
            if (newInfantry > 0) unitsAt(n.capital).merge(UnitType.INFANTRY.id(), newInfantry, Integer::sum);
            if (n.gold < 0) { n.gold = 0; n.troops *= 0.995; } // unpaid troops desert
        }

        for (Op op : new ArrayList<>(ops)) step(op, counts);
    }

    private void step(Op op, Map<String, Integer> counts) {
        Nation att = nations.get(op.attacker);
        if (att == null) { ops.remove(op); return; }
        if (op.unitType != null) { stepUnitAttack(op, att); return; }
        Nation def = ownerOf(op.chunk);
        if (def != null && !att.enemies.contains(def.id)) {
            ops.remove(op); att.troops += Math.max(0, op.troops); return;
        }
        double rate = op.troops * 0.4;
        // resistance: defending troops are spread over all the defender's chunks
        double defense = def == null ? 0.5 : 0.3 * def.troops / Math.max(1, counts.getOrDefault(def.id, 1));
        op.progress += rate - defense;
        op.troops -= op.troops * 0.01 + defense * 0.1;
        if (def != null) def.troops = Math.max(0, def.troops - rate * 0.02);

        if (op.progress >= 100) {
            capture(op, att, def);
        } else if (op.troops < 1 || op.progress <= -20) {
            ops.remove(op);
            att.troops += Math.max(0, op.troops);
            events.toNation(att, "Occupation of chunk " + op.chunk.replace(",", ", ") + " failed.");
            if (def != null) events.toNation(def, "You repelled the attack on " + op.chunk.replace(",", ", ") + ".");
        }
    }

    private void stepUnitAttack(Op op, Nation att) {
        UnitType unit = UnitType.parse(op.unitType);
        if (unit == null) { ops.remove(op); return; }
        Nation def = ownerOf(op.chunk);
        if (def != null && !att.enemies.contains(def.id)) { failUnitAttack(op, att, "The target is no longer hostile."); return; }
        String domain = attackDomain(unit);
        int upgrade = att.upgrades.getOrDefault(domain + "_attack", 0);
        double attackPower = attackStat(unit) * op.troops * (1 + 0.15 * upgrade);
        double defensePower = def == null ? 0 : defensePowerAt(def, op.chunk, domain);
        boolean oilfield = terrainType(op.chunk) == 1;
        double terrain = unit.domain.equals("ground") && oilfield ? 0.85 : 1.0;
        op.progress += attackPower * terrain - defensePower + 20;
        if (defensePower > 0) {
            double losses = Math.min(op.troops, Math.max(1, Math.ceil(defensePower / Math.max(1, attackPower) * op.troops * 0.20)));
            op.troops -= losses;
            damageGarrison(op.chunk, domain, Math.max(1, (int) Math.ceil(attackPower * 0.04)));
        }
        if (op.progress >= 100) capture(op, att, def);
        else if (op.troops <= 0 || op.progress <= -40) failUnitAttack(op, att, "Your " + unit.title + " were repelled.");
    }

    private static String attackDomain(UnitType type) {
        if (type.airAttack > 0) return "air";
        if (type.waterAttack > 0) return "water";
        return "ground";
    }
    private static int attackStat(UnitType type) {
        return switch (attackDomain(type)) { case "air" -> type.airAttack; case "water" -> type.waterAttack; default -> type.groundAttack; };
    }
    private double defensePowerAt(Nation defender, String chunk, String domain) {
        double total = 0;
        for (Map.Entry<String, Integer> e : unitsAt(chunk).entrySet()) {
            UnitType unit = UnitType.parse(e.getKey()); if (unit == null) continue;
            int value = switch (domain) { case "air" -> unit.airDefense; case "water" -> unit.waterDefense; default -> unit.groundDefense; };
            total += value * e.getValue();
        }
        return total * (1 + 0.15 * defender.upgrades.getOrDefault(domain + "_defense", 0));
    }
    private void damageGarrison(String chunk, String domain, int damage) {
        Map<String, Integer> defenders = unitsAt(chunk);
        List<String> ids = new ArrayList<>(defenders.keySet());
        for (String id : ids) {
            UnitType unit = UnitType.parse(id); if (unit == null) continue;
            int def = switch (domain) { case "air" -> unit.airDefense; case "water" -> unit.waterDefense; default -> unit.groundDefense; };
            if (def <= 0) continue;
            int loss = Math.min(damage, defenders.getOrDefault(id, 0));
            defenders.compute(id, (k, v) -> v == loss ? null : v - loss);
            damage -= loss;
            if (damage <= 0) break;
        }
    }
    private void failUnitAttack(Op op, Nation att, String message) {
        ops.remove(op);
        if (op.troops > 0 && op.sourceChunk != null) unitsAt(op.sourceChunk).merge(op.unitType, (int) Math.ceil(op.troops), Integer::sum);
        events.toNation(att, message);
    }

    private void capture(Op op, Nation att, Nation def) {
        ops.remove(op);
        if (op.unitType != null) garrisons.remove(op.chunk); // the defending stack was defeated
        owner.put(op.chunk, att.id);
        if (att.cities == null) att.cities = new LinkedHashSet<>();
        if (def != null && def.cities != null && def.cities.remove(op.chunk)) att.cities.add(op.chunk);
        if (op.unitType == null) att.troops += Math.max(0, op.troops);
        else if (op.troops > 0) unitsAt(op.chunk).merge(op.unitType, (int) Math.ceil(op.troops), Integer::sum);
        String where = op.chunk.replace(",", ", ");
        events.toNation(att, "Captured chunk " + where + (def != null ? " from " + def.name : "") + "!");
        if (def == null) return;
        events.toNation(def, "You lost chunk " + where + " to " + att.name + "!");
        if (chunksOf(def) == 0) {
            events.toAll(def.name + " was wiped out by " + att.name + "!");
            removeNation(def);
        } else if (op.chunk.equals(def.capital)) {
            for (Map.Entry<String, String> e : owner.entrySet())
                if (e.getValue().equals(def.id)) { def.capital = e.getKey(); break; }
            if (def.cities == null) def.cities = new LinkedHashSet<>();
            def.cities.add(def.capital);
            events.toNation(def, "Your capital fell. New capital: " + def.capital.replace(",", ", "));
        }
    }
}
