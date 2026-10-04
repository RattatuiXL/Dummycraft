package dev.dummycraft;

import java.util.*;

/**
 * All DummyCraft rules live here. No Minecraft imports, so this file can be
 * compiled and unit-tested on its own. Chunks are addressed as "x,z" strings
 * (chunk coordinates, overworld only).
 */
public class Game {
    public static final double START_GOLD = 100, START_TROOPS = 20, RECRUIT_COST = 5;
    public static final int MIN_ATTACK = 5;
    public static final double CAPITAL_GOLD = 0.5;   // flat gold/s from the capital, whatever its terrain
    public static final double UPKEEP = 0.005;       // gold/s per troop

    /** Chat colours (names of net.minecraft.ChatFormatting constants), handed out in order. */
    static final String[] COLORS = {"RED", "BLUE", "GREEN", "YELLOW", "LIGHT_PURPLE", "AQUA",
            "GOLD", "DARK_GREEN", "DARK_AQUA", "DARK_PURPLE", "DARK_RED", "WHITE"};

    /** Per-second yield per chunk type: {gold, manpower}. 0 = farmland, 1 = mine, 2 = plains. */
    static final double[][] YIELD = {{0.05, 0.08}, {0.40, 0.0}, {0.10, 0.03}};
    static final String[] TYPE_NAMES = {"farmland", "mine", "plains"};

    public Map<String, Nation> nations = new LinkedHashMap<>();
    public Map<String, String> owner = new HashMap<>();   // "x,z" -> nation id
    public List<Op> ops = new ArrayList<>();               // active occupations

    public transient Map<UUID, String> invites = new HashMap<>();
    public transient Events events = new Events() {
        public void toNation(Nation n, String msg) {}
        public void toAll(String msg) {}
    };

    public static class Nation {
        public String id, name, color, capital;
        public UUID leader;
        public Set<UUID> members = new LinkedHashSet<>();
        public Set<String> enemies = new LinkedHashSet<>();
        public Set<String> peaceOffers = new LinkedHashSet<>(); // nations that offered peace to us
        public double gold, troops;                              // troops = reserve (not deployed)
    }

    public static class Op {
        public String chunk, attacker;
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
    public static int cx(String k) { return Integer.parseInt(k.substring(0, k.indexOf(','))); }
    public static int cz(String k) { return Integer.parseInt(k.substring(k.indexOf(',') + 1)); }

    /** Each chunk deterministically has a resource type, like fixed resource points in Dummynation. */
    public static int type(String chunk) {
        return Math.floorMod(cx(chunk) * 73856093 ^ cz(chunk) * 19349663, 3);
    }
    public static String typeName(String chunk) { return TYPE_NAMES[type(chunk)]; }

    public Nation nationOf(UUID p) {
        for (Nation n : nations.values()) if (n.members.contains(p)) return n;
        return null;
    }
    public Nation byName(String s) { return nations.get(s.toLowerCase(Locale.ROOT)); }
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
    public double score(Nation n) { return chunksOf(n) * 10 + n.troops + deployed(n) + n.gold / 10; }

    /** "Do not spread too thin": holding far more land than your army can cover cuts income. */
    public double efficiency(Nation n, int chunks) {
        double over = Math.max(0, chunks - (8 + n.troops / 4));
        return Math.max(0.25, 1 - 0.03 * over);
    }

    boolean adjacentToOwned(Nation n, int x, int z) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] v : d) if (n.id.equals(owner.get(key(x + v[0], z + v[1])))) return true;
        return false;
    }

    /** Wilderness is open; your own land is open; enemy land is open to raiders (you are at war). */
    public boolean canBuild(UUID p, int x, int z) {
        Nation o = at(x, z);
        if (o == null) return true;
        Nation mine = nationOf(p);
        if (mine == o) return true;
        return mine != null && mine.enemies.contains(o.id);
    }

    // ---------------------------------------------------------------- commands

    public R create(UUID p, String name, int x, int z) {
        if (nationOf(p) != null) return err("You are already in a nation.");
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return err("Name must be 3-16 letters, digits or underscores.");
        String id = name.toLowerCase(Locale.ROOT);
        if (nations.containsKey(id)) return err("That name is taken.");
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (owner.containsKey(key(x + dx, z + dz))) return err("Too close to claimed land (keep 2 chunks clear).");
        Nation n = new Nation();
        n.id = id; n.name = name; n.leader = p; n.members.add(p);
        n.gold = START_GOLD; n.troops = START_TROOPS;
        n.capital = key(x, z);
        Set<String> used = new HashSet<>();
        for (Nation o : nations.values()) used.add(o.color);
        n.color = COLORS[nations.size() % COLORS.length];
        for (String c : COLORS) if (!used.contains(c)) { n.color = c; break; }
        nations.put(id, n);
        owner.put(n.capital, id);
        events.toAll("The nation of " + name + " has been founded!");
        return ok("You founded " + name + ". This chunk (" + typeName(n.capital) + ") is your capital.");
    }

    public R recruit(Nation n, int amount) {
        if (amount < 1 || amount > 1000) return err("Recruit between 1 and 1000 troops.");
        double cost = amount * RECRUIT_COST;
        if (n.gold < cost) return err("You need " + (int) Math.ceil(cost) + " gold (you have " + (int) n.gold + ").");
        n.gold -= cost;
        n.troops += amount;
        return ok("Recruited " + amount + " troops for " + (int) cost + " gold.");
    }

    public R attack(Nation n, int x, int z, int troops) {
        String k = key(x, z);
        if (troops < MIN_ATTACK) return err("Send at least " + MIN_ATTACK + " troops.");
        if (troops > n.troops) return err("Not enough troops in reserve (" + (int) n.troops + ").");
        Nation o = ownerOf(k);
        if (o == n) return err("You already own this chunk.");
        if (o != null && !n.enemies.contains(o.id)) return err("You are not at war with " + o.name + ".");
        if (!adjacentToOwned(n, x, z)) return err("You can only attack chunks next to your territory.");
        if (opAt(x, z) != null) return err("This chunk is already under attack.");
        n.troops -= troops;
        Op op = new Op();
        op.chunk = k; op.attacker = n.id; op.troops = troops;
        ops.add(op);
        if (o != null) events.toNation(o, n.name + " is attacking your chunk at " + x + ", " + z + "!");
        return ok("Occupation of chunk " + x + ", " + z + " started with " + troops + " troops.");
    }

    public R retreat(Nation n, int x, int z) {
        Op op = opAt(x, z);
        if (op == null || !op.attacker.equals(n.id)) return err("You have no occupation in this chunk.");
        ops.remove(op);
        n.troops += Math.max(0, op.troops);
        return ok("Troops withdrawn.");
    }

    public R war(Nation a, Nation b) {
        if (a == b) return err("You cannot declare war on yourself.");
        if (a.enemies.contains(b.id)) return err("You are already at war with " + b.name + ".");
        a.enemies.add(b.id); b.enemies.add(a.id);
        a.peaceOffers.remove(b.id); b.peaceOffers.remove(a.id);
        events.toAll(a.name + " declared war on " + b.name + "!");
        return ok("War declared on " + b.name + ".");
    }

    public R peace(Nation a, Nation b) {
        if (!a.enemies.contains(b.id)) return err("You are not at war with " + b.name + ".");
        if (a.peaceOffers.contains(b.id)) {
            a.enemies.remove(b.id); b.enemies.remove(a.id);
            a.peaceOffers.remove(b.id); b.peaceOffers.remove(a.id);
            for (Iterator<Op> it = ops.iterator(); it.hasNext(); ) {
                Op o = it.next();
                Nation t = ownerOf(o.chunk);
                boolean ab = o.attacker.equals(a.id) && t == b, ba = o.attacker.equals(b.id) && t == a;
                if (ab || ba) { nations.get(o.attacker).troops += Math.max(0, o.troops); it.remove(); }
            }
            events.toAll(a.name + " and " + b.name + " made peace.");
            return ok("Peace with " + b.name + ".");
        }
        b.peaceOffers.add(a.id);
        events.toNation(b, a.name + " offers peace. Accept with /nation peace " + a.name);
        return ok("Peace offer sent to " + b.name + ".");
    }

    public R invite(Nation n, UUID target) {
        if (nationOf(target) != null) return err("That player is already in a nation.");
        invites.put(target, n.id);
        return ok("Invitation sent.");
    }

    public R join(UUID p, Nation n) {
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
        if (n.leader.equals(p)) return err("Leaders cannot leave; use /nation disband.");
        n.members.remove(p);
        return ok("You left " + n.name + ".");
    }

    public R disband(UUID p) {
        Nation n = nationOf(p);
        if (n == null) return err("You are not in a nation.");
        if (!n.leader.equals(p)) return err("Only the leader can disband the nation.");
        events.toAll("The nation of " + n.name + " has been dissolved.");
        removeNation(n);
        return ok("Nation dissolved.");
    }

    void removeNation(Nation n) {
        owner.values().removeIf(id -> id.equals(n.id));
        ops.removeIf(o -> o.attacker.equals(n.id));
        for (Nation o : nations.values()) { o.enemies.remove(n.id); o.peaceOffers.remove(n.id); }
        nations.remove(n.id);
    }

    // ---------------------------------------------------------------- simulation (call once per second)

    public void tick() {
        Map<String, Integer> counts = new HashMap<>();
        for (String id : owner.values()) counts.merge(id, 1, Integer::sum);

        // economy: resources are tied to individual chunks, capital counts triple
        Map<String, double[]> income = new HashMap<>();
        for (Map.Entry<String, String> e : owner.entrySet()) {
            Nation n = nations.get(e.getValue());
            if (n == null) continue;
            double[] y = YIELD[type(e.getKey())];
            boolean cap = e.getKey().equals(n.capital);
            double m = (cap ? 3 : 1) * efficiency(n, counts.get(n.id));
            double[] a = income.computeIfAbsent(n.id, k -> new double[2]);
            a[0] += y[0] * m + (cap ? CAPITAL_GOLD : 0);
            a[1] += y[1] * m;
        }
        for (Nation n : nations.values()) {
            double[] a = income.getOrDefault(n.id, new double[2]);
            n.gold += a[0] - (n.troops + deployed(n)) * UPKEEP;
            n.troops += a[1];
            if (n.gold < 0) { n.gold = 0; n.troops *= 0.995; } // unpaid troops desert
        }

        for (Op op : new ArrayList<>(ops)) step(op, counts);
    }

    private void step(Op op, Map<String, Integer> counts) {
        Nation att = nations.get(op.attacker);
        if (att == null) { ops.remove(op); return; }
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

    private void capture(Op op, Nation att, Nation def) {
        ops.remove(op);
        owner.put(op.chunk, att.id);
        att.troops += Math.max(0, op.troops);
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
            events.toNation(def, "Your capital fell. New capital: " + def.capital.replace(",", ", "));
        }
    }
}
