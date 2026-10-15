package pl.zabojstwo;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RankingManager {

    private final JavaPlugin plugin;
    private final File file;
    private final int startRating;
    private final Map<UUID, Integer> ratings = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public RankingManager(JavaPlugin plugin, int startRating) {
        this.plugin = plugin;
        this.startRating = startRating;
        this.file = new File(plugin.getDataFolder(), "ranking.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = cfg.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ratings.put(id, section.getInt(key + ".rating", startRating));
                names.put(id, section.getString(key + ".name", "?"));
            } catch (IllegalArgumentException ignored) {
                // zly UUID w pliku - pomijamy
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : ratings.entrySet()) {
            String path = "players." + e.getKey();
            cfg.set(path + ".name", names.getOrDefault(e.getKey(), "?"));
            cfg.set(path + ".rating", e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException ex) {
            dirty = true;
            plugin.getLogger().severe("Nie udało się zapisać ranking.yml: " + ex.getMessage());
        }
    }

    public void saveIfDirty() {
        if (dirty) {
            dirty = false;
            save();
        }
    }

    /** Rejestruje gracza (nowy dostaje ranking startowy) i aktualizuje jego nick. */
    public void register(Player player) {
        ratings.putIfAbsent(player.getUniqueId(), startRating);
        names.put(player.getUniqueId(), player.getName());
        dirty = true;
    }

    public int get(UUID id) {
        return ratings.getOrDefault(id, startRating);
    }

    public void set(Player player, int value) {
        ratings.put(player.getUniqueId(), Math.max(0, value));
        names.put(player.getUniqueId(), player.getName());
        dirty = true;
    }

    /** Szuka gracza po nicku (takze offline, jesli kiedys byl na serwerze). */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public String getName(UUID id) {
        return names.getOrDefault(id, "?");
    }

    /** Pozycja w rankingu (1 = najlepszy). */
    public int getPosition(UUID id) {
        int mine = get(id);
        int pos = 1;
        for (Map.Entry<UUID, Integer> e : ratings.entrySet()) {
            if (!e.getKey().equals(id) && e.getValue() > mine) pos++;
        }
        return pos;
    }

    public List<Map.Entry<UUID, Integer>> top(int limit) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(ratings.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(limit, list.size()));
    }
}
