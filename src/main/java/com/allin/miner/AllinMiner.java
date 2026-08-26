
package com.allin.miner;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Particle;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class AllinMiner extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, Location> points = new LinkedHashMap<>();
    private final Map<UUID, Stats> stats = new HashMap<>();
    private final Map<UUID, BukkitTask> mining = new HashMap<>();
    private File pointsFile;
    private NamespacedKey pointKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        pointKey = new NamespacedKey(this, "miner_point");
        pointsFile = new File(getDataFolder(), "points.yml");
        loadPoints();
        loadStats();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("miner")).setExecutor(this);
        Objects.requireNonNull(getCommand("miner")).setTabCompleter(this);
        getServer().getScheduler().runTaskTimer(this, this::tickPoints, 0L, 10L);
        getServer().getScheduler().runTaskTimer(this, this::respawnAll, 1200L, 1200L);
        getLogger().info("ALLIN Miner enabled.");
    }

    @Override public void onDisable() {
        mining.values().forEach(BukkitTask::cancel);
        savePoints(); saveStats();
    }

    private void loadPoints() {
        var c = new org.bukkit.configuration.file.YamlConfiguration();
        try { c.load(pointsFile); } catch (Exception ignored) {}
        var sec = c.getConfigurationSection("points");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            String world = sec.getString(id + ".world");
            if (world == null) continue;
            World w = Bukkit.getWorld(world);
            if (w == null) continue;
            points.put(id, new Location(w, sec.getDouble(id+".x"), sec.getDouble(id+".y"), sec.getDouble(id+".z")));
            setupBlock(points.get(id));
        }
    }
    private void savePoints() {
        var c = new org.bukkit.configuration.file.YamlConfiguration();
        for (var e: points.entrySet()) {
            String p="points."+e.getKey(); Location l=e.getValue();
            c.set(p+".world", l.getWorld().getName()); c.set(p+".x", l.getBlockX());
            c.set(p+".y", l.getBlockY()); c.set(p+".z", l.getBlockZ());
        }
        try { pointsFile.getParentFile().mkdirs(); c.save(pointsFile); } catch(Exception ex){ getLogger().warning(ex.getMessage());}
    }
    private void loadStats() {
        File f = new File(getDataFolder(), "players.yml");
        var c = new org.bukkit.configuration.file.YamlConfiguration();
        try { c.load(f); } catch(Exception ignored){}
        var sec=c.getConfigurationSection("players"); if(sec==null)return;
        for(String s:sec.getKeys(false)) {
            try { UUID u=UUID.fromString(s); Stats st=new Stats(); st.level=c.getInt("players."+s+".level",1); st.progress=c.getInt("players."+s+".progress",0); stats.put(u,st);}catch(Exception ignored){}
        }
    }
    private void saveStats() {
        File f=new File(getDataFolder(),"players.yml");
        var c=new org.bukkit.configuration.file.YamlConfiguration();
        for(var e:stats.entrySet()){String p="players."+e.getKey(); c.set(p+".level",e.getValue().level); c.set(p+".progress",e.getValue().progress);}
        try{c.save(f);}catch(Exception ex){getLogger().warning(ex.getMessage());}
    }
    private Stats stat(Player p){return stats.computeIfAbsent(p.getUniqueId(),u->new Stats());}

    private void setupBlock(Location l) {
        Block b=l.getBlock();
        b.getChunk().load();
        b.setMetadata("allin_miner", new org.bukkit.metadata.FixedMetadataValue(this, true));
        if (b.getType()==Material.AIR || !getConfig().getBoolean("point.keep-current-block",false)) randomize(b);
    }
    private void randomize(Block b) {
        var entries=getConfig().getMapList("ores");
        if(entries.isEmpty()){b.setType(Material.IRON_ORE);return;}
        double r=ThreadLocalRandom.current().nextDouble(), sum=0;
        Material chosen=Material.IRON_ORE;
        for(var m:entries){
            Object chanceValue = m.get("chance");
            double chance = chanceValue instanceof Number n ? n.doubleValue() : 0.0;
            sum += chance;
            if(r<=sum){try{chosen=Material.valueOf(String.valueOf(m.get("material")));}catch(Exception ignored){} break;}
        }
        b.setType(chosen);
    }
    private boolean isPoint(Block b){
        return b.hasMetadata("allin_miner");
    }
    private void tickPoints(){
        for(Location l:points.values()){
            Block b=l.getBlock();
            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(.5,.7,.5), 2, .25,.25,.25,.01);
        }
    }
    private void respawnAll(){ for(Location l:points.values()){ if(!mining.values().stream().anyMatch(t->!t.isCancelled())) randomize(l.getBlock()); } }

    @EventHandler public void onBreak(BlockBreakEvent e){
        if(isPoint(e.getBlock())) e.setCancelled(true);
    }
    @EventHandler public void onInteract(PlayerInteractEvent e){
        if(e.getAction()!=Action.LEFT_CLICK_BLOCK && e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Block b=e.getClickedBlock(); if(b==null||!isPoint(b))return;
        e.setCancelled(true);
        Player p=e.getPlayer();
        if(p.getGameMode()!=GameMode.ADVENTURE)return;
        if(mining.containsKey(p.getUniqueId())){p.sendActionBar("§cТы уже добываешь руду.");return;}
        startMining(p,b);
    }
    private void startMining(Player p, Block b){
        ItemStack tool=p.getInventory().getItemInMainHand();
        int level=stat(p).level;
        double base=getConfig().getDouble("mining.base-seconds",10);
        double min=getConfig().getDouble("mining.min-seconds",1);
        double levelReduction=getConfig().getDouble("mining.level-seconds-reduction",1);
        double seconds=Math.max(min,base-(level-1)*levelReduction);
        int eff=tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        double effReduction=getConfig().getDouble("mining.efficiency-seconds-reduction",0.75);
        seconds=Math.max(min,seconds-eff*effReduction);
        if(!isPickaxe(tool.getType())) seconds=Math.max(min,seconds+getConfig().getDouble("mining.no-pickaxe-penalty",3));
        long ticks=Math.max(20,Math.round(seconds*20));
        p.sendActionBar("§e⛏ Добыча: §f"+String.format(Locale.US,"%.1f",seconds)+" сек.");
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(this, new Runnable(){
            long left=ticks;
            public void run(){
                if(!p.isOnline() || p.getGameMode()!=GameMode.ADVENTURE || !isPoint(b)){stop(p);return;}
                left-=5;
                double progress=1.0-(double)left/ticks;
                p.sendActionBar("§e⛏ "+bar(progress)+" §7"+String.format(Locale.US,"%.1f",Math.max(0,left/20.0))+"с");
                if(left<=0){finish(p,b);stop(p);}
            }
        },0,5);
        mining.put(p.getUniqueId(),task);
    }
    private String bar(double x){int n=20,k=(int)Math.round(x*n);return "§a"+"▰".repeat(Math.max(0,k))+"§7"+"▰".repeat(Math.max(0,n-k));}
    private void stop(Player p){BukkitTask t=mining.remove(p.getUniqueId());if(t!=null)t.cancel();}
    private void finish(Player p, Block b){
        Material m=b.getType();
        ItemStack tool=p.getInventory().getItemInMainHand();

        // Every completed attempt consumes durability, whether mining succeeds or fails.
        damageTool(p, tool);

        double failChance = getFailureChance(tool.getType());
        if (ThreadLocalRandom.current().nextDouble() < failChance) {
            p.sendMessage("§c✖ Добыча не удалась! §7Шанс неудачи: §f" + percent(failChance));
            return;
        }

        Stats s=stat(p);
        int lvl=s.level;
        boolean silk = tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0;

        int amount;
        Material rewardMaterial;
        if (silk) {
            // Silk Touch intentionally changes the special mining reward to exactly one resource.
            amount = 1;
            rewardMaterial = resourceFor(m);
        } else {
            rewardMaterial = m;
            int min=getConfig().getInt("reward.min",1);
            int baseMax=getConfig().getInt("reward.max",3);
            int levelBonus=Math.max(0, lvl-1);
            boolean fixedRare = m == Material.DIAMOND_ORE || m == Material.ANCIENT_DEBRIS;
            int max = fixedRare ? 1 : baseMax + levelBonus;
            amount=ThreadLocalRandom.current().nextInt(min, max+1);

            int fortune=tool.getEnchantmentLevel(Enchantment.FORTUNE);
            double fortuneChance=getConfig().getDouble("fortune.chances."+fortune, 0.0);
            if (fortune > 0 && ThreadLocalRandom.current().nextDouble() < fortuneChance) amount++;
        }

        give(p, new ItemStack(rewardMaterial, amount));

        // Progress is awarded only for a successful non-Silk-Touch block extraction.
        if (!silk) {
            s.progress++;
            int[] req={0,1000,3000,5000,7000};
            if(lvl<5 && s.progress>=req[lvl]){
                s.level++; s.progress=0;
                p.sendMessage("§6§lШАХТЁР §eУровень повышен: §f"+s.level+"§e!");
            }

            // Level 5 bonus is disabled for Silk Touch and therefore handled only here.
            if(lvl>=5 && ThreadLocalRandom.current().nextDouble()<getConfig().getDouble("level-5.extra-diamond-chance",0.05)){
                give(p, new ItemStack(Material.DIAMOND,1));
                p.sendMessage("§b✦ Бонус Шахтёра: §f+1 алмаз!");
            }
        }

        String rewardName=pretty(rewardMaterial);
        p.sendMessage("§a⛏ Добыча успешна: §f"+amount+"x "+rewardName+(silk?" §7(Silk Touch)":""));
        b.setType(Material.AIR);
    }

    private void give(Player p, ItemStack item){
        HashMap<Integer,ItemStack> rest=p.getInventory().addItem(item);
        rest.values().forEach(i->p.getWorld().dropItemNaturally(p.getLocation(),i));
    }

    private double getFailureChance(Material type){
        String key=switch(type){
            case WOODEN_PICKAXE -> "wooden";
            case STONE_PICKAXE -> "stone";
            case IRON_PICKAXE -> "iron";
            case GOLDEN_PICKAXE -> "golden";
            case DIAMOND_PICKAXE -> "diamond";
            case NETHERITE_PICKAXE -> "netherite";
            default -> "no-pickaxe";
        };
        return getConfig().getDouble("failure-chances."+key, 0.60);
    }

    private String percent(double value){return String.format(Locale.US,"%.0f%%",value*100.0);}

    private Material resourceFor(Material ore){
        return switch(ore){
            case COAL_ORE, DEEPSLATE_COAL_ORE -> Material.COAL;
            case COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.RAW_COPPER;
            case IRON_ORE, DEEPSLATE_IRON_ORE -> Material.RAW_IRON;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE -> Material.RAW_GOLD;
            case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> Material.REDSTONE;
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> Material.LAPIS_LAZULI;
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> Material.EMERALD;
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Material.DIAMOND;
            case ANCIENT_DEBRIS -> Material.ANCIENT_DEBRIS;
            default -> ore;
        };
    }

    private void damageTool(Player p, ItemStack tool){
        if(tool==null || tool.getType()==Material.AIR) return;
        ItemMeta meta=tool.getItemMeta();
        if(!(meta instanceof Damageable damageable)) return;
        int max=tool.getType().getMaxDurability();
        if(max<=0) return;

        int unbreaking=tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        boolean consumes=true;
        if(unbreaking>0){
            // Standard-style Unbreaking chance: each level adds another chance to avoid damage.
            consumes=ThreadLocalRandom.current().nextInt(unbreaking+1)==0;
        }
        if(!consumes) return;

        int next=damageable.getDamage()+1;
        if(next>=max){
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            p.sendMessage("§c✖ Твоя кирка сломалась!");
        }else{
            damageable.setDamage(next);
            tool.setItemMeta(meta);
        }
    }

    private boolean isPickaxe(Material m){return m.name().endsWith("_PICKAXE");}
    private String pretty(Material m){return m.name().toLowerCase(Locale.ROOT).replace('_',' ');}

    @Override public boolean onCommand(CommandSender s, Command cmd,String label,String[] a){
        if(a.length==0){s.sendMessage("§e/miner add|remove|list|respawn|stats|reload");return true;}
        if(a[0].equalsIgnoreCase("stats")){
            Player p=a.length>1?Bukkit.getPlayerExact(a[1]):(s instanceof Player?(Player)s:null);
            if(p==null){s.sendMessage("§cИгрок не найден.");return true;}
            Stats st=stat(p);s.sendMessage("§6Шахтёр §f"+p.getName()+" §7— уровень §e"+st.level+" §7, прогресс §f"+st.progress);return true;
        }
        if(!s.hasPermission("allinminer.admin")){s.sendMessage("§cНет прав.");return true;}
        switch(a[0].toLowerCase()){
            case "add" -> {if(!(s instanceof Player p)){s.sendMessage("Только из игры.");return true;} Block b=p.getTargetBlockExact(6); if(b==null){s.sendMessage("§cСмотри на блок.");return true;} String id="point"+(points.size()+1);points.put(id,b.getLocation());setupBlock(b.getLocation());savePoints();s.sendMessage("§aТочка создана: §f"+id);}
            case "remove" -> {if(!(s instanceof Player p)){return true;} Block b=p.getTargetBlockExact(6);if(b==null||!isPoint(b)){s.sendMessage("§cЭто не точка.");return true;} points.entrySet().removeIf(e->same(e.getValue(),b.getLocation()));b.removeMetadata("allin_miner",this);savePoints();s.sendMessage("§aТочка удалена.");}
            case "list" -> {s.sendMessage("§6Точек: §f"+points.size());points.forEach((id,l)->s.sendMessage("§7"+id+" §f"+l.getWorld().getName()+" "+l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ()));}
            case "respawn" -> {respawnAll();s.sendMessage("§aРуды обновлены.");}
            case "reload" -> {reloadConfig();s.sendMessage("§aКонфигурация перезагружена.");}
            default -> s.sendMessage("§e/miner add|remove|list|respawn|stats|reload");
        }
        return true;
    }
    private boolean same(Location a,Location b){return a.getWorld().equals(b.getWorld())&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){
        if(a.length==1)return List.of("add","remove","list","respawn","stats","reload").stream().filter(x->x.startsWith(a[0].toLowerCase())).toList();
        return Collections.emptyList();
    }
    static class Stats{int level=1,progress=0;}
}
