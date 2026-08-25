package com.allin.miner;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class AllinMiner extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String VERSION = "1.0.1";

    private final Map<String, Location> points = new LinkedHashMap<>();
    private final Map<UUID, Stats> stats = new LinkedHashMap<>();
    private final Map<UUID, BukkitTask> miningTasks = new LinkedHashMap<>();
    private final Map<UUID, Location> miningLocations = new LinkedHashMap<>();

    private File pointsFile;
    private File statsFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        pointsFile = new File(getDataFolder(), "points.yml");
        statsFile = new File(getDataFolder(), "players.yml");

        loadPoints();
        loadStats();

        getServer().getPluginManager().registerEvents(this, this);

        var command = getCommand("miner");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        // Visual highlight for special mining blocks.
        getServer().getScheduler().runTaskTimer(this, this::highlightPoints, 0L, 10L);

        // Respawn all free points every 60 seconds.
        getServer().getScheduler().runTaskTimer(this, this::respawnFreePoints, 1200L, 1200L);

        // Periodic data save.
        getServer().getScheduler().runTaskTimer(this, this::saveStats, 6000L, 6000L);

        getLogger().info("ALLIN Miner " + VERSION + " enabled.");
    }

    @Override
    public void onDisable() {
        miningTasks.values().forEach(BukkitTask::cancel);
        miningTasks.clear();
        miningLocations.clear();
        savePoints();
        saveStats();
    }

    private void loadPoints() {
        points.clear();

        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(pointsFile);
        } catch (Exception ignored) {
        }

        var section = cfg.getConfigurationSection("points");
        if (section == null) {
            return;
        }

        for (String id : section.getKeys(false)) {
            String worldName = section.getString(id + ".world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }

            Location location = new Location(
                    world,
                    section.getInt(id + ".x"),
                    section.getInt(id + ".y"),
                    section.getInt(id + ".z")
            );

            points.put(id, location);

            Block block = location.getBlock();
            if (block.getType() == Material.AIR
                    || !getConfig().getBoolean("point.keep-current-block", false)) {
                randomize(block);
            }
        }
    }

    private void savePoints() {
        YamlConfiguration cfg = new YamlConfiguration();

        for (var entry : points.entrySet()) {
            String path = "points." + entry.getKey();
            Location location = entry.getValue();

            cfg.set(path + ".world", location.getWorld().getName());
            cfg.set(path + ".x", location.getBlockX());
            cfg.set(path + ".y", location.getBlockY());
            cfg.set(path + ".z", location.getBlockZ());
        }

        try {
            pointsFile.getParentFile().mkdirs();
            cfg.save(pointsFile);
        } catch (Exception ex) {
            getLogger().warning("Could not save points.yml: " + ex.getMessage());
        }
    }

    private void loadStats() {
        stats.clear();

        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(statsFile);
        } catch (Exception ignored) {
        }

        var section = cfg.getConfigurationSection("players");
        if (section == null) {
            return;
        }

        for (String id : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                Stats stat = new Stats();
                stat.level = Math.max(1, Math.min(5, cfg.getInt("players." + id + ".level", 1)));
                stat.progress = Math.max(0, cfg.getInt("players." + id + ".progress", 0));
                stats.put(uuid, stat);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveStats() {
        YamlConfiguration cfg = new YamlConfiguration();

        for (var entry : stats.entrySet()) {
            String path = "players." + entry.getKey();
            cfg.set(path + ".level", entry.getValue().level);
            cfg.set(path + ".progress", entry.getValue().progress);
        }

        try {
            statsFile.getParentFile().mkdirs();
            cfg.save(statsFile);
        } catch (Exception ex) {
            getLogger().warning("Could not save players.yml: " + ex.getMessage());
        }
    }

    private Stats getStats(Player player) {
        return stats.computeIfAbsent(player.getUniqueId(), ignored -> new Stats());
    }

    private boolean isMiningPoint(Block block) {
        for (Location point : points.values()) {
            if (sameBlock(point, block.getLocation())) {
                return true;
            }
        }
        return false;
    }

    private boolean sameBlock(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return false;
        }

        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void highlightPoints() {
        for (Location point : points.values()) {
            Block block = point.getBlock();

            if (block.getType() == Material.AIR) {
                continue;
            }

            block.getWorld().spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    block.getLocation().add(0.5, 0.7, 0.5),
                    2,
                    0.25, 0.25, 0.25,
                    0.01
            );
        }
    }

    private void respawnFreePoints() {
        for (Location point : points.values()) {
            if (!isPointBeingMined(point)) {
                randomize(point.getBlock());
            }
        }
    }

    private boolean isPointBeingMined(Location point) {
        for (Location location : miningLocations.values()) {
            if (sameBlock(location, point)) {
                return true;
            }
        }
        return false;
    }

    private void randomize(Block block) {
        List<Map<?, ?>> entries = getConfig().getMapList("ores");

        if (entries.isEmpty()) {
            block.setType(Material.IRON_ORE);
            return;
        }

        double roll = ThreadLocalRandom.current().nextDouble();
        double accumulated = 0.0;
        Material chosen = Material.IRON_ORE;

        for (Map<?, ?> entry : entries) {
            Object chanceValue = entry.get("chance");
            Object materialValue = entry.get("material");

            if (!(chanceValue instanceof Number) || materialValue == null) {
                continue;
            }

            accumulated += ((Number) chanceValue).doubleValue();

            if (roll <= accumulated) {
                try {
                    chosen = Material.valueOf(String.valueOf(materialValue).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    chosen = Material.IRON_ORE;
                }
                break;
            }
        }

        block.setType(chosen);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isMiningPoint(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || !isMiningPoint(block)) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();

        if (player.getGameMode() != GameMode.ADVENTURE) {
            return;
        }

        if (miningTasks.containsKey(player.getUniqueId())) {
            player.sendActionBar("§cТы уже добываешь руду.");
            return;
        }

        startMining(player, block);
    }

    private void startMining(Player player, Block block) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        Stats stat = getStats(player);

        double baseSeconds = getConfig().getDouble("mining.base-seconds", 10.0);
        double minSeconds = getConfig().getDouble("mining.min-seconds", 1.0);
        double levelReduction = getConfig().getDouble("mining.level-seconds-reduction", 1.0);
        double efficiencyReduction = getConfig().getDouble("mining.efficiency-seconds-reduction", 0.75);

        double seconds = Math.max(
                minSeconds,
                baseSeconds - ((stat.level - 1) * levelReduction)
        );

        int efficiency = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        seconds = Math.max(minSeconds, seconds - (efficiency * efficiencyReduction));

        if (!isPickaxe(tool.getType())) {
            seconds += getConfig().getDouble("mining.no-pickaxe-penalty", 3.0);
        }

        long totalTicks = Math.max(20L, Math.round(seconds * 20.0));

        player.sendActionBar(
                "§e⛏ Добыча: §f"
                        + String.format(Locale.US, "%.1f", seconds)
                        + " сек."
        );

        UUID uuid = player.getUniqueId();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private long remaining = totalTicks;

            @Override
            public void run() {
                if (!player.isOnline()
                        || player.getGameMode() != GameMode.ADVENTURE
                        || !isMiningPoint(block)
                        || block.getType() == Material.AIR) {
                    stopMining(uuid);
                    return;
                }

                remaining -= 5L;

                double progress = 1.0 - ((double) remaining / totalTicks);

                player.sendActionBar(
                        "§e⛏ " + progressBar(progress)
                                + " §7" + String.format(
                                Locale.US,
                                "%.1f",
                                Math.max(0.0, remaining / 20.0)
                        ) + "с"
                );

                if (remaining <= 0) {
                    finishMining(player, block);
                    stopMining(uuid);
                }
            }
        }, 0L, 5L);

        miningTasks.put(uuid, task);
        miningLocations.put(uuid, block.getLocation());
    }

    private String progressBar(double progress) {
        int size = 20;
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * size);

        return "§a"
                + "▰".repeat(Math.max(0, filled))
                + "§7"
                + "▰".repeat(Math.max(0, size - filled));
    }

    private void stopMining(UUID uuid) {
        BukkitTask task = miningTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        miningLocations.remove(uuid);
    }

    private void finishMining(Player player, Block block) {
        Material material = block.getType();

        int rewardMin = Math.max(1, getConfig().getInt("reward.min", 1));
        int rewardMax = Math.max(rewardMin, getConfig().getInt("reward.max", 3));

        int amount = ThreadLocalRandom.current().nextInt(rewardMin, rewardMax + 1);

        giveOrDrop(player, new ItemStack(material, amount));

        Stats stat = getStats(player);
        stat.progress++;

        int required = requiredForNextLevel(stat.level);

        if (stat.level < 5 && stat.progress >= required) {
            stat.level++;
            stat.progress = 0;

            player.sendMessage(
                    "§6§lШАХТЁР §eУровень повышен: §f" + stat.level + "§e!"
            );
        }

        if (stat.level >= 5
                && ThreadLocalRandom.current().nextDouble() < 0.10) {
            giveOrDrop(player, new ItemStack(Material.DIAMOND, 1));
            player.sendMessage("§b✦ Бонус Шахтёра: §f+1 алмаз!");
        }

        player.sendMessage(
                "§a⛏ Добыто: §f" + amount + "x " + pretty(material)
        );

        // The special point becomes empty until the next respawn cycle.
        block.setType(Material.AIR);
    }

    private int requiredForNextLevel(int currentLevel) {
        return switch (currentLevel) {
            case 1 -> 1000;
            case 2 -> 2000;
            case 3 -> 3000;
            case 4 -> 5000;
            default -> Integer.MAX_VALUE;
        };
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);

        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private boolean isPickaxe(Material material) {
        return material.name().endsWith("_PICKAXE");
    }

    private String pretty(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            sender.sendMessage("§e/miner add|remove|list|respawn|stats|reload");
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            Player target;

            if (args.length > 1) {
                target = Bukkit.getPlayerExact(args[1]);
            } else {
                target = sender instanceof Player ? (Player) sender : null;
            }

            if (target == null) {
                sender.sendMessage("§cИгрок не найден.");
                return true;
            }

            Stats stat = getStats(target);

            sender.sendMessage(
                    "§6Шахтёр §f" + target.getName()
                            + " §7— уровень §e" + stat.level
                            + " §7, прогресс §f" + stat.progress
            );
            return true;
        }

        if (!sender.hasPermission("allinminer.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cЭту команду нужно выполнить в игре.");
                    return true;
                }

                Player player = (Player) sender;
                Block block = player.getTargetBlockExact(6);

                if (block == null) {
                    sender.sendMessage("§cСмотри на блок в радиусе 6 блоков.");
                    return true;
                }

                String id = nextPointId();
                points.put(id, block.getLocation());
                randomize(block);
                savePoints();

                sender.sendMessage("§aТочка создана: §f" + id);
            }

            case "remove" -> {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cЭту команду нужно выполнить в игре.");
                    return true;
                }

                Player player = (Player) sender;
                Block block = player.getTargetBlockExact(6);

                if (block == null || !isMiningPoint(block)) {
                    sender.sendMessage("§cЭто не точка ALLIN Miner.");
                    return true;
                }

                points.entrySet().removeIf(
                        entry -> sameBlock(entry.getValue(), block.getLocation())
                );

                savePoints();
                sender.sendMessage("§aТочка удалена.");
            }

            case "list" -> {
                sender.sendMessage("§6Точек: §f" + points.size());

                for (var entry : points.entrySet()) {
                    Location location = entry.getValue();

                    sender.sendMessage(
                            "§7" + entry.getKey()
                                    + " §f" + location.getWorld().getName()
                                    + " " + location.getBlockX()
                                    + " " + location.getBlockY()
                                    + " " + location.getBlockZ()
                    );
                }
            }

            case "respawn" -> {
                respawnFreePoints();
                sender.sendMessage("§aРуды обновлены.");
            }

            case "reload" -> {
                reloadConfig();
                sender.sendMessage("§aКонфигурация перезагружена.");
            }

            default -> sender.sendMessage(
                    "§e/miner add|remove|list|respawn|stats|reload"
            );
        }

        return true;
    }

    private String nextPointId() {
        int number = points.size() + 1;

        while (points.containsKey("point" + number)) {
            number++;
        }

        return "point" + number;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(
                    List.of("add", "remove", "list", "respawn", "stats", "reload")
            );

            String prefix = args[0].toLowerCase(Locale.ROOT);

            return values.stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }

        return Collections.emptyList();
    }

    private static final class Stats {
        private int level = 1;
        private int progress = 0;
    }
}
