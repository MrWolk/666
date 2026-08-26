package com.allin.processor;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AllinProcessor extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, Stats> stats = new HashMap<>();
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    private final Map<UUID, ItemStack> reservedInput = new HashMap<>();
    private File playersFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        playersFile = new File(getDataFolder(), "players.yml");
        loadStats();

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("processor") != null) {
            getCommand("processor").setExecutor(this);
            getCommand("processor").setTabCompleter(this);
        }

        getServer().getScheduler().runTaskTimer(this, this::saveStats, 6000L, 6000L);
        getLogger().info("ALLINProcessor 1.0.0 enabled.");
    }

    @Override
    public void onDisable() {
        activeTasks.values().forEach(BukkitTask::cancel);
        activeTasks.clear();
        saveStats();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.LECTERN) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();

        Material output = getOutputFor(hand.getType());
        if (output == null) {
            player.sendActionBar(Component.text("§cДержи в руке блок руды для переработки."));
            return;
        }

        event.setCancelled(true);

        if (activeTasks.containsKey(player.getUniqueId())) {
            player.sendActionBar(Component.text("§cПереработка уже идёт."));
            return;
        }

        if (hand.getAmount() <= 0) return;

        // Consume exactly 1 ore block at start.
        ItemStack consumed = new ItemStack(hand.getType(), 1);
        reservedInput.put(player.getUniqueId(), consumed);

        if (hand.getAmount() == 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }

        startProcessing(player, clicked.getLocation(), consumed, output);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!activeTasks.containsKey(uuid)) return;

        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) task.cancel();

        ItemStack refund = reservedInput.remove(uuid);
        if (refund != null) {
            giveOrDrop(event.getPlayer(), refund);
        }
    }

    private void startProcessing(Player player, Location station, ItemStack input, Material output) {
        int seconds = Math.max(1, getConfig().getInt("processing.seconds", 5));
        long totalTicks = seconds * 20L;
        UUID uuid = player.getUniqueId();

        player.sendActionBar(Component.text("§e§l⚙ §fПереработка началась..."));

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private long remaining = totalTicks;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelAndRefund(player);
                    return;
                }

                if (getConfig().getBoolean("processing.cancel-if-too-far", true)) {
                    double maxDistance = getConfig().getDouble("processing.max-distance", 4.0);
                    if (!player.getWorld().equals(station.getWorld())
                            || player.getLocation().distanceSquared(station) > maxDistance * maxDistance) {
                        player.sendMessage("§cПереработка отменена: ты отошёл слишком далеко.");
                        cancelAndRefund(player);
                        return;
                    }
                }

                remaining -= 5L;
                double progress = 1.0 - ((double) remaining / totalTicks);
                player.sendActionBar(Component.text(buildBar(progress, remaining)));

                if (remaining <= 0) {
                    completeProcessing(player, input, output);
                    finishTask(uuid);
                }
            }
        }, 0L, 5L);

        activeTasks.put(uuid, task);
    }

    private String buildBar(double progress, long remainingTicks) {
        int size = 14;
        int filled = (int)Math.round(Math.max(0.0, Math.min(1.0, progress)) * size);
        int percent = (int)Math.round(Math.max(0.0, Math.min(1.0, progress)) * 100.0);
        double secondsLeft = Math.max(0.0, remainingTicks / 20.0);

        return "§e§l⚙ §a§l"
                + "█".repeat(Math.max(0, filled))
                + "§8"
                + "█".repeat(Math.max(0, size - filled))
                + " §f§l" + percent + "%"
                + " §7(" + String.format(Locale.US, "%.1f", secondsLeft) + "с)";
    }

    private void completeProcessing(Player player, ItemStack input, Material output) {
        reservedInput.remove(player.getUniqueId());

        Stats stat = getStats(player);
        int reward = calculateReward(stat.level);

        giveOrDrop(player, new ItemStack(output, reward));

        stat.progress++;

        if (stat.level == 1 && stat.progress >= getConfig().getInt("levels.level-2-required", 2000)) {
            stat.level = 2;
            stat.progress = 0;
            player.sendMessage("§6§lПЕРЕРАБОТЧИК §eУровень повышен: §f2§e!");
        } else if (stat.level == 2 && stat.progress >= getConfig().getInt("levels.level-3-required", 6000)) {
            stat.level = 3;
            stat.progress = 0;
            player.sendMessage("§6§lПЕРЕРАБОТЧИК §eУровень повышен: §f3 §7(максимальный)§e!");
        }

        boolean bonusDiamond = false;
        if (stat.level >= 3) {
            double chance = getConfig().getDouble("levels.level-3-diamond-chance", 0.01);
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                giveOrDrop(player, new ItemStack(Material.DIAMOND, 1));
                bonusDiamond = true;
            }
        }

        player.sendMessage("§a⚙ Переработка завершена: §f1x "
                + pretty(input.getType()) + " §7→ §f" + reward + "x " + pretty(output));

        if (bonusDiamond) {
            player.sendMessage("§b✦ Бонус Переработчика: §f+1 алмаз!");
        }

        saveStats();
    }

    private int calculateReward(int level) {
        return switch (level) {
            case 1 -> ThreadLocalRandom.current().nextInt(1, 4); // 1-3
            case 2 -> ThreadLocalRandom.current().nextInt(2, 4); // 2-3
            default -> 3; // level 3
        };
    }

    private Material getOutputFor(Material input) {
        ConfigurationSection section = getConfig().getConfigurationSection("recipes");
        if (section == null) return null;

        String outputName = section.getString(input.name());
        if (outputName == null || outputName.isBlank()) return null;

        try {
            return Material.valueOf(outputName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid output material in config for " + input + ": " + outputName);
            return null;
        }
    }

    private Stats getStats(Player player) {
        return stats.computeIfAbsent(player.getUniqueId(), ignored -> new Stats());
    }

    private void loadStats() {
        stats.clear();
        YamlConfiguration cfg = new YamlConfiguration();

        try {
            cfg.load(playersFile);
        } catch (Exception ignored) {
        }

        ConfigurationSection section = cfg.getConfigurationSection("players");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                Stats stat = new Stats();
                stat.level = Math.max(1, Math.min(3, cfg.getInt("players." + key + ".level", 1)));
                stat.progress = Math.max(0, cfg.getInt("players." + key + ".progress", 0));
                stats.put(uuid, stat);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveStats() {
        if (playersFile == null) return;

        YamlConfiguration cfg = new YamlConfiguration();

        for (Map.Entry<UUID, Stats> entry : stats.entrySet()) {
            String path = "players." + entry.getKey();
            cfg.set(path + ".level", entry.getValue().level);
            cfg.set(path + ".progress", entry.getValue().progress);
        }

        try {
            playersFile.getParentFile().mkdirs();
            cfg.save(playersFile);
        } catch (Exception ex) {
            getLogger().warning("Could not save players.yml: " + ex.getMessage());
        }
    }

    private void cancelAndRefund(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) task.cancel();

        ItemStack refund = reservedInput.remove(uuid);
        if (refund != null) {
            giveOrDrop(player, refund);
        }
    }

    private void finishTask(UUID uuid) {
        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) task.cancel();
        reservedInput.remove(uuid);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private String pretty(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/processor stats [player] §7| §e/processor reload");
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
            } else {
                target = sender instanceof Player ? (Player)sender : null;
            }

            if (target == null) {
                sender.sendMessage("§cИгрок не найден.");
                return true;
            }

            Stats stat = getStats(target);
            String next = switch (stat.level) {
                case 1 -> stat.progress + " / " + getConfig().getInt("levels.level-2-required", 2000);
                case 2 -> stat.progress + " / " + getConfig().getInt("levels.level-3-required", 6000);
                default -> stat.progress + " (∞)";
            };

            sender.sendMessage("§6Переработчик §f" + target.getName()
                    + " §7— уровень §e" + stat.level
                    + " §7, прогресс §f" + next);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("allinprocessor.admin")) {
                sender.sendMessage("§cНет прав.");
                return true;
            }
            reloadConfig();
            sender.sendMessage("§aALLINProcessor: config.yml перезагружен.");
            return true;
        }

        sender.sendMessage("§e/processor stats [player] §7| §e/processor reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("stats", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return Collections.emptyList();
    }

    private static final class Stats {
        private int level = 1;
        private int progress = 0;
    }
}
