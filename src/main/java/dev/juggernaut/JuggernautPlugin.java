package dev.juggernaut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.UUID;

public final class JuggernautPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final String TEAM_NAME = "juggernaut";

    private UUID juggernaut;
    private String juggernautName = "";
    private NamespacedKey trackerKey;
    private String previousTeam; // team the Juggernaut was in before we recolored their glow

    // ----------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        trackerKey = new NamespacedKey(this, "tracker");
        String saved = getConfig().getString("juggernaut", "");
        if (saved != null && !saved.isBlank()) {
            try {
                juggernaut = UUID.fromString(saved.trim());
                var off = Bukkit.getOfflinePlayer(juggernaut);
                juggernautName = off.getName() != null ? off.getName() : "Juggernaut";
            } catch (IllegalArgumentException ignored) {
                juggernaut = null;
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"juggernaut", "track"}) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        int interval = Math.max(1, getConfig().getInt("actionbar-interval-ticks", 10));
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, interval);
        Player online = juggernautPlayer();
        if (online != null) applyJuggernaut(online);
    }

    @Override
    public void onDisable() {
        Player online = juggernautPlayer();
        if (online != null) removeGlow(online);
    }

    // ----------------------------------------------------------- Juggernaut

    private Player juggernautPlayer() {
        return juggernaut == null ? null : Bukkit.getPlayer(juggernaut);
    }

    private void setJuggernaut(Player p) {
        Player old = juggernautPlayer();
        if (old != null) removeGlow(old);
        juggernaut = p.getUniqueId();
        juggernautName = p.getName();
        getConfig().set("juggernaut", juggernaut.toString());
        saveConfig();
        applyJuggernaut(p);
    }

    private void clearJuggernaut() {
        Player old = juggernautPlayer();
        if (old != null) removeGlow(old);
        juggernaut = null;
        juggernautName = "";
        getConfig().set("juggernaut", "");
        saveConfig();
    }

    /** Glow through walls, with a colored outline. */
    private void applyJuggernaut(Player p) {
        p.setGlowing(true);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team prev = sb.getEntryTeam(p.getName());
        if (prev != null && !prev.getName().equals(TEAM_NAME)) previousTeam = prev.getName();
        Team team = sb.getTeam(TEAM_NAME);
        if (team == null) team = sb.registerNewTeam(TEAM_NAME);
        team.color(glowColor());
        team.addEntry(p.getName());
    }

    private void removeGlow(Player p) {
        p.setGlowing(false);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(TEAM_NAME);
        if (team != null) team.removeEntry(p.getName());
        if (previousTeam != null) {
            Team prev = sb.getTeam(previousTeam);
            if (prev != null) prev.addEntry(p.getName());
            previousTeam = null;
        }
    }

    private NamedTextColor glowColor() {
        String name = getConfig().getString("glow-color", "RED");
        NamedTextColor c = NamedTextColor.NAMES.value(name == null ? "red" : name.toLowerCase());
        return c != null ? c : NamedTextColor.RED;
    }

    // ------------------------------------------------------------ tracking

    private void tick() {
        Player j = juggernautPlayer();
        if (j != null && !j.isDead() && !j.isGlowing()) j.setGlowing(true); // keep it on

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!holdingCompass(p)) continue;
            if (juggernaut == null) {
                p.sendActionBar(Component.text("No Juggernaut is set.", NamedTextColor.GRAY));
            } else if (j == null) {
                p.sendActionBar(Component.text("Juggernaut " + juggernautName + " is offline.", NamedTextColor.GRAY));
            } else if (p.getUniqueId().equals(juggernaut)) {
                p.sendActionBar(Component.text("You are the Juggernaut. Survive!", NamedTextColor.RED));
            } else if (!p.getWorld().equals(j.getWorld())) {
                p.sendActionBar(Component.text("Juggernaut " + j.getName() + " is in " + worldName(j.getWorld()) + ".", NamedTextColor.YELLOW));
            } else {
                p.setCompassTarget(j.getLocation());
                p.sendActionBar(trackLine(p, j));
            }
        }
    }

    private Component trackLine(Player from, Player target) {
        var a = from.getLocation();
        var b = target.getLocation();
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double dist = a.distance(b);

        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = ((targetYaw - a.getYaw()) % 360 + 540) % 360 - 180;     // -180..180, 0 = straight ahead
        int idx = (int) Math.floorMod(Math.round(diff / 45.0), 8);

        return Component.text("Juggernaut ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(target.getName() + "  ", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                .append(Component.text(ARROWS[idx] + " ", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(Math.round(dist) + "m", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false));
    }

    // ------------------------------------------------------ tracker compass

    /** Each tracker compass remembers the "epoch" it was made in; a Juggernaut death starts a new epoch. */
    private int epoch() {
        return getConfig().getInt("compass-epoch", 0);
    }

    private ItemStack trackerCompass() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Juggernaut Tracker", NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Hold me to hunt the Juggernaut.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(trackerKey, PersistentDataType.INTEGER, epoch());
        it.setItemMeta(m);
        return it;
    }

    /** True for a tracker compass made before the Juggernaut last died. */
    private boolean isExpiredTracker(ItemStack it) {
        if (it == null || it.getType() != Material.COMPASS || !it.hasItemMeta()) return false;
        Integer made = it.getItemMeta().getPersistentDataContainer().get(trackerKey, PersistentDataType.INTEGER);
        return made != null && made < epoch();
    }

    private void removeExpiredTrackers(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isExpiredTracker(inv.getItem(i))) inv.setItem(i, null);
        }
        if (isExpiredTracker(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    private void giveCompass(Player p) {
        p.getInventory().addItem(trackerCompass()).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private static boolean holdingCompass(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.COMPASS
                || p.getInventory().getItemInOffHand().getType() == Material.COMPASS;
    }

    private static String worldName(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "the Nether";
            case THE_END -> "the End";
            case NORMAL -> "the Overworld";
            default -> w.getName();
        };
    }

    // -------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        removeExpiredTrackers(p);   // they were offline when the Juggernaut died
        if (p.getUniqueId().equals(juggernaut)) {
            juggernautName = p.getName();
            getServer().getScheduler().runTask(this, () -> applyJuggernaut(p));
            Bukkit.broadcast(Component.text("The Juggernaut " + p.getName() + " has returned!", NamedTextColor.RED));
        }
        if (getConfig().getBoolean("give-compass-on-join", false)) giveCompass(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (p.getUniqueId().equals(juggernaut)) getServer().getScheduler().runTask(this, () -> applyJuggernaut(p));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(juggernaut)) return;
        Player killer = p.getKiller();
        String by = killer != null ? " by " + killer.getName() : "";
        Bukkit.broadcast(Component.text("The Juggernaut " + p.getName() + " has fallen" + by + "!",
                NamedTextColor.DARK_RED, TextDecoration.BOLD));
        clearJuggernaut();

        // Juggernaut is dead: every tracker compass is deleted (drops, online players now, offline players on join)
        getConfig().set("compass-epoch", epoch() + 1);
        saveConfig();
        e.getDrops().removeIf(this::isExpiredTracker);
        for (Player online : Bukkit.getOnlinePlayers()) removeExpiredTrackers(online);
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("track")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            giveCompass(p);
            p.sendMessage(Component.text("Hold the compass to hunt the Juggernaut.", NamedTextColor.GREEN));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("/" + label + " <set <player>|clear|info>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> {
                Player target = args.length >= 2 ? getServer().getPlayerExact(args[1])
                        : (sender instanceof Player self ? self : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                setJuggernaut(target);
                Bukkit.broadcast(Component.text(target.getName() + " is now the Juggernaut! Hunt them down with /track",
                        NamedTextColor.RED, TextDecoration.BOLD));
            }
            case "clear" -> {
                clearJuggernaut();
                sender.sendMessage(Component.text("Juggernaut cleared.", NamedTextColor.GREEN));
            }
            case "info" -> sender.sendMessage(juggernaut == null
                    ? Component.text("No Juggernaut is set.", NamedTextColor.GRAY)
                    : Component.text("The Juggernaut is " + juggernautName + (juggernautPlayer() == null ? " (offline)." : "."), NamedTextColor.RED));
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("juggernaut")) return List.of();
        if (args.length == 1) return List.of("set", "clear", "info");
        if (args.length == 2 && args[0].equalsIgnoreCase("set"))
            return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
