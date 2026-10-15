package pl.zabojstwo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ZabojstwoPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final Title.Times TIMES = Title.Times.times(
            Duration.ofMillis(150),   // pojawianie się
            Duration.ofMillis(2000),  // czas wyświetlania
            Duration.ofMillis(500)    // znikanie
    );

    private RankingManager ranking;
    private int kFactor;
    private int minChange;
    private int environmentLoss;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kFactor = getConfig().getInt("k-factor", 32);
        minChange = getConfig().getInt("min-change", 1);
        environmentLoss = getConfig().getInt("environment-death-loss", 10);

        ranking = new RankingManager(this, getConfig().getInt("start-rating", 1000));
        ranking.load();
        for (Player p : Bukkit.getOnlinePlayers()) ranking.register(p);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ranking").setExecutor(this);
        getCommand("top").setExecutor(this);

        // zapis co 30 sekund (asynchronicznie), jesli cos sie zmienilo
        getServer().getScheduler().runTaskTimerAsynchronously(this, ranking::saveIfDirty, 600L, 600L);
    }

    @Override
    public void onDisable() {
        if (ranking != null) ranking.save();
    }

    // ---------- Wejście / wyjście (bez wiadomości) ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        ranking.register(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
    }

    // ---------- Śmierć + ranking ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Nic na czacie - wiadomość idzie jako title
        event.deathMessage(null);

        int victimOld = ranking.get(victim.getUniqueId());
        Component victimTitle = Component.text("☠ " + victim.getName(), NamedTextColor.RED, TextDecoration.BOLD);

        if (killer != null && !killer.equals(victim)) {
            int killerOld = ranking.get(killer.getUniqueId());

            // Elo: im wyższy ranking zabójcy względem ofiary, tym mniej zyskuje
            double expected = 1.0 / (1.0 + Math.pow(10.0, (victimOld - killerOld) / 400.0));
            int change = Math.max(minChange, (int) Math.round(kFactor * (1.0 - expected)));
            int loss = Math.min(change, victimOld); // ranking nie spada poniżej 0

            ranking.set(killer, killerOld + change);
            ranking.set(victim, victimOld - loss);

            // Zabójca
            killer.showTitle(Title.title(
                    Component.text("ZABÓJSTWO", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text(victim.getName(), NamedTextColor.WHITE)
                            .append(Component.text("  +" + change + " pkt", NamedTextColor.GREEN))
                            .append(Component.text(" (" + (killerOld + change) + ")", NamedTextColor.GRAY)),
                    TIMES));
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.7f);

            // Ofiara
            victim.showTitle(Title.title(
                    Component.text("ZGINĄŁEŚ", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("zabity przez " + killer.getName(), NamedTextColor.GRAY)
                            .append(Component.text("  -" + loss + " pkt", NamedTextColor.RED))
                            .append(Component.text(" (" + (victimOld - loss) + ")", NamedTextColor.GRAY)),
                    TIMES));

            // Pozostali gracze - "wiadomość o zabójstwie" jako title
            Title others = Title.title(victimTitle, describeKill(killer), TIMES);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(killer) && !p.equals(victim)) p.showTitle(others);
            }
        } else {
            // Śmierć bez zabójcy (spadek, lawa, mob...)
            int loss = Math.min(environmentLoss, victimOld);
            ranking.set(victim, victimOld - loss);
            Component reason = describeEnvironment(victim);

            victim.showTitle(Title.title(
                    Component.text("ZGINĄŁEŚ", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    reason.append(Component.text("  -" + loss + " pkt", NamedTextColor.RED))
                          .append(Component.text(" (" + (victimOld - loss) + ")", NamedTextColor.GRAY)),
                    TIMES));

            Title others = Title.title(victimTitle, reason, TIMES);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(victim)) p.showTitle(others);
            }
        }
    }

    private Component describeKill(Player killer) {
        Component msg = Component.text("zabity przez ", NamedTextColor.GRAY)
                .append(Component.text(killer.getName(), NamedTextColor.GREEN));
        ItemStack item = killer.getInventory().getItemInMainHand();
        if (!item.getType().isAir()) {
            msg = msg.append(Component.text(" używając ", NamedTextColor.GRAY))
                     .append(item.displayName().color(NamedTextColor.GOLD));
        }
        return msg;
    }

    private Component describeEnvironment(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            return Component.text("zabity przez ", NamedTextColor.GRAY)
                    .append(byEntity.getDamager().name().color(NamedTextColor.YELLOW));
        }

        String reason = "zginął";
        if (last != null) {
            reason = switch (last.getCause()) {
                case FALL -> "spadł z wysokości";
                case FIRE, FIRE_TICK -> "spłonął";
                case HOT_FLOOR -> "spłonął na magmie";
                case LAVA -> "wykąpał się w lawie";
                case DROWNING -> "utonął";
                case SUFFOCATION -> "udusił się w ścianie";
                case STARVATION -> "zmarł z głodu";
                case VOID -> "wpadł w otchłań";
                case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "wyleciał w powietrze";
                case LIGHTNING -> "został rażony piorunem";
                case POISON -> "został zatruty";
                case MAGIC -> "padł ofiarą magii";
                case WITHER -> "uschnął";
                case CONTACT -> "nadział się na kaktusa";
                case FREEZE -> "zamarzł";
                case FLY_INTO_WALL -> "rozbił się o ścianę";
                case FALLING_BLOCK -> "został przygnieciony";
                case SUICIDE -> "odebrał sobie życie";
                default -> "zginął";
            };
        }
        return Component.text(reason, NamedTextColor.GRAY);
    }

    // ---------- Komendy ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("top")) {
            List<Map.Entry<UUID, Integer>> top = ranking.top(10);
            sender.sendMessage(Component.text("━━ TOP 10 RANKING ━━", NamedTextColor.GOLD, TextDecoration.BOLD));
            int i = 1;
            for (Map.Entry<UUID, Integer> e : top) {
                sender.sendMessage(Component.text(i++ + ". ", NamedTextColor.YELLOW)
                        .append(Component.text(ranking.getName(e.getKey()), NamedTextColor.WHITE))
                        .append(Component.text(" - " + e.getValue() + " pkt", NamedTextColor.GREEN)));
            }
            return true;
        }

        // /ranking [gracz]
        UUID target;
        if (args.length >= 1) {
            target = ranking.findByName(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Nie znaleziono gracza " + args[0] + " w rankingu.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p.getUniqueId();
        } else {
            sender.sendMessage(Component.text("Użycie: /ranking <gracz>", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("Ranking gracza ", NamedTextColor.GRAY)
                .append(Component.text(ranking.getName(target), NamedTextColor.WHITE))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(ranking.get(target) + " pkt", NamedTextColor.GREEN))
                .append(Component.text("  (miejsce #" + ranking.getPosition(target) + ")", NamedTextColor.GOLD)));
        return true;
    }
}
