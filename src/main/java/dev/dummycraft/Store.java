package dev.dummycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** JSON persistence. Saved to config/dummycraft.json (one world per server). */
final class Store {
    private static final Logger LOG = LoggerFactory.getLogger(DummyCraft.ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dummycraft.json"); }

    static Game load() {
        try {
            if (Files.exists(file())) {
                Game g = GSON.fromJson(Files.readString(file()), Game.class);
                if (g != null) return g;
            }
        } catch (Exception e) {
            LOG.error("Could not read {}; starting with an empty world", file(), e);
        }
        return new Game();
    }

    static void save(Game g) {
        try {
            Files.createDirectories(file().getParent());
            Path tmp = file().resolveSibling("dummycraft.json.tmp");
            Files.writeString(tmp, GSON.toJson(g));
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.error("Could not save {}", file(), e);
        }
    }
}
