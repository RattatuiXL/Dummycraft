package dev.dummycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** JSON persistence scoped to the active Minecraft world. */
final class Store {
    private static final Logger LOG = LoggerFactory.getLogger(DummyCraft.ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("dummycraft.json");
    }

    static Game load(MinecraftServer server) {
        Path file = file(server);
        try {
            if (Files.exists(file)) {
                Game g = GSON.fromJson(Files.readString(file), Game.class);
                if (g != null) {
                    // Fill fields added after the first prototype's saves were written.
                    if (g.nations == null) g.nations = new java.util.LinkedHashMap<>();
                    if (g.owner == null) g.owner = new java.util.HashMap<>();
                    if (g.ops == null) g.ops = new java.util.ArrayList<>();
                    if (g.turnOrder == null) g.turnOrder = new java.util.ArrayList<>();
                    if (g.garrisons == null) g.garrisons = new java.util.HashMap<>();
                    if (g.terrain == null) g.terrain = new java.util.HashMap<>();
                    if (g.resourceQuality == null) g.resourceQuality = new java.util.HashMap<>();
                    if (g.markerIds == null) g.markerIds = new java.util.HashMap<>();
                    if (g.scenarioCountries == null) g.scenarioCountries = new java.util.ArrayList<>();
                    if (g.mapChunks == null) g.mapChunks = new java.util.HashSet<>();
                    if (g.waterChunks == null) g.waterChunks = new java.util.HashSet<>();
                    for (Game.Nation n : g.nations.values()) {
                        if (n.borderColor == null) n.borderColor = n.color;
                        if (n.cities == null) n.cities = new java.util.LinkedHashSet<>();
                        if (n.capital != null) n.cities.add(n.capital);
                        if (n.upgrades == null) n.upgrades = new java.util.LinkedHashMap<>();
                        if (n.troops >= 1 && n.capital != null) {
                            int legacyTroops = (int)n.troops;
                            g.garrisons.computeIfAbsent(n.capital, k -> new java.util.LinkedHashMap<>())
                                    .merge(UnitType.INFANTRY.id(), legacyTroops, Integer::sum);
                            n.troops -= legacyTroops;
                        }
                    }
                    return g;
                }
            }
        } catch (Exception e) {
            LOG.error("Could not read {}; starting with an empty world", file, e);
        }
        return new Game();
    }

    static void save(Game g, MinecraftServer server) {
        Path file = file(server);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("dummycraft.json.tmp");
            Files.writeString(tmp, GSON.toJson(g));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.error("Could not save {}", file, e);
        }
    }
}
