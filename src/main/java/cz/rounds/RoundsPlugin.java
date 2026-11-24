package cz.rounds;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

public class RoundsPlugin extends JavaPlugin implements Listener {

    // ====== NASTAVENI ======
    private static final int MAX_PER_TEAM = 5;
    private static final int POINTS_TO_WIN = 5;
    private static final int FREEZE_SECONDS = 5;   // odpocet na zacatku kola
    private static final int ROUND_SECONDS = 150;  // limit kola
    private static final int BETWEEN_SECONDS = 5;  // pauza mezi koly

    enum Team {
        RED("Cerveni", NamedTextColor.RED, Color.RED, "red"),
        BLUE("Modri", NamedTextColor.BLUE, Color.BLUE, "blue");

        final String name;
        final NamedTextColor color;
        final Color armor;
        final String key;

        Team(String name, NamedTextColor color, Color armor, String key) {
            this.name = name;
            this.color = color;
            this.armor = armor;
            this.key = key;
        }

        Team other() { return this == RED ? BLUE : RED; }
    }

    enum State { IDLE, FREEZE, FIGHT, BETWEEN }

    private final Map<UUID, Team> teams = new LinkedHashMap<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Map<Team, Integer> score = new EnumMap<>(Team.class);

    private State state = State.IDLE;
    private int timer;
    private int round;
    private boolean gameOver;
    private BukkitTask task;

    private Scoreboard board;
    private Objective objective;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (state != State.IDLE) stopGame();
    }

    // ====== PRIKAZY ======
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/rounds <join red|blue | leave | start | stop | setlobby | setspawn red|blue>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "join" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) { p.sendMessage("/rounds join <red|blue>"); return true; }
                Team t = parseTeam(args[1]);
                if (t == null) { p.sendMessage("Tym: red nebo blue."); return true; }
                joinTeam(p, t);
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) return true;
                if (!teams.containsKey(p.getUniqueId())) { p.sendMessage("Nejsi v minihre."); return true; }
                removePlayer(p, true);
                p.sendMessage(Component.text("Opustil jsi minihru.", NamedTextColor.YELLOW));
            }
            case "start" -> {
                if (!admin(sender)) return true;
                startGame(sender);
            }
            case "stop" -> {
                if (!admin(sender)) return true;
                if (state == State.IDLE) { sender.sendMessage("Hra nebezi."); return true; }
                broadcast(Component.text("Hra byla ukoncena adminem.", NamedTextColor.RED));
                stopGame();
            }
            case "setlobby" -> {
                if (!admin(sender) || !(sender instanceof Player p)) return true;
                getConfig().set("lobby", p.getLocation());
                saveConfig();
                p.sendMessage("Lobby nastaveno.");
            }
            case "setspawn" -> {
                if (!admin(sender) || !(sender instanceof Player p)) return true;
                Team t = args.length > 1 ? parseTeam(args[1]) : null;
                if (t == null) { p.sendMessage("/rounds setspawn <red|blue>"); return true; }
                getConfig().set("spawn." + t.key, p.getLocation());
                saveConfig();
                p.sendMessage("Spawn tymu " + t.name + " nastaven.");
            }
            default -> sender.sendMessage("Neznamy podprikaz.");
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("rounds.admin")) return true;
        s.sendMessage(Component.text("Na to nemas pravo.", NamedTextColor.RED));
        return false;
    }

    private Team parseTeam(String s) {
        return switch (s.toLowerCase()) {
            case "red", "cerveni" -> Team.RED;
            case "blue", "modri" -> Team.BLUE;
            default -> null;
        };
    }

    // ====== TYMY ======
    private int teamSize(Team t) {
        int n = 0;
        for (Team x : teams.values()) if (x == t) n++;
        return n;
    }

    private void joinTeam(Player p, Team t) {
        if (state != State.IDLE) { p.sendMessage(Component.text("Hra uz bezi.", NamedTextColor.RED)); return; }
        if (teams.get(p.getUniqueId()) == t) { p.sendMessage("Uz jsi v tomto tymu."); return; }
        if (teamSize(t) >= MAX_PER_TEAM) { p.sendMessage(Component.text("Tym je plny.", NamedTextColor.RED)); return; }
        teams.put(p.getUniqueId(), t);
        broadcast(Component.text(p.getName() + " vstoupil do tymu ", NamedTextColor.GRAY)
                .append(Component.text(t.name, t.color)));
    }

    private void removePlayer(Player p, boolean teleportOut) {
        UUID id = p.getUniqueId();
        boolean wasAlive = alive.remove(id);
        teams.remove(id);
        if (state != State.IDLE) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            resetPlayer(p);
            if (teleportOut) teleportLobby(p);
            // pokud nekdo odejde uprostred hry
            if (teamSize(Team.RED) == 0 || teamSize(Team.BLUE) == 0) {
                broadcast(Component.text("Jeden tym ma 0 hracu, hra konci.", NamedTextColor.RED));
                stopGame();
                return;
            }
            if (wasAlive) checkRoundEnd();
            updateBoard();
        }
    }

    // ====== HRA ======
    private void startGame(CommandSender sender) {
        if (state != State.IDLE) { sender.sendMessage("Hra uz bezi."); return; }
        if (teamSize(Team.RED) < 1 || teamSize(Team.BLUE) < 1) {
            sender.sendMessage("V kazdem tymu musi byt aspon 1 hrac.");
            return;
        }
        if (getLocation("spawn.red") == null || getLocation("spawn.blue") == null) {
            sender.sendMessage("Nejdriv nastav spawny: /rounds setspawn red|blue");
            return;
        }
        score.put(Team.RED, 0);
        score.put(Team.BLUE, 0);
        round = 0;
        gameOver = false;

        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("rounds", Criteria.DUMMY, Component.text("RUNDA"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());

        for (Team t : Team.values()) {
            org.bukkit.scoreboard.Team bt = board.registerNewTeam(t.key);
            bt.color(t.color);
            bt.setAllowFriendlyFire(false);
            bt.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                    org.bukkit.scoreboard.Team.OptionStatus.FOR_OWN_TEAM);
        }
        for (Map.Entry<UUID, Team> e : teams.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            board.getTeam(e.getValue().key).addEntry(p.getName());
            p.setScoreboard(board);
        }

        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        nextRound();
    }

    private void nextRound() {
        round++;
        alive.clear();
        state = State.FREEZE;
        timer = FREEZE_SECONDS;

        for (Map.Entry<UUID, Team> e : teams.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            alive.add(p.getUniqueId());
            p.setSpectatorTarget(null);
            resetPlayer(p);
            p.teleport(Objects.requireNonNull(getLocation("spawn." + e.getValue().key)));
            giveKit(p, e.getValue());
        }
        broadcast(Component.text("Kolo " + round + " - priprav se!", NamedTextColor.YELLOW));
        updateBoard();
    }

    private void tick() {
        if (state == State.IDLE) return;
        timer--;
        switch (state) {
            case FREEZE -> {
                if (timer > 0) {
                    showTitle(Component.text(String.valueOf(timer), NamedTextColor.YELLOW), Component.empty());
                } else {
                    state = State.FIGHT;
                    timer = ROUND_SECONDS;
                    showTitle(Component.text("FIGHT!", NamedTextColor.GREEN), Component.empty());
                }
            }
            case FIGHT -> {
                if (timer <= 0) {
                    int r = aliveCount(Team.RED), b = aliveCount(Team.BLUE);
                    broadcast(Component.text("Cas vyprsel!", NamedTextColor.YELLOW));
                    endRound(r > b ? Team.RED : b > r ? Team.BLUE : null);
                }
            }
            case BETWEEN -> {
                if (timer <= 0) {
                    if (gameOver) stopGame();
                    else nextRound();
                    return;
                }
            }
            default -> {}
        }
        updateBoard();
    }

    private int aliveCount(Team t) {
        int n = 0;
        for (UUID id : alive) if (teams.get(id) == t) n++;
        return n;
    }

    private void checkRoundEnd() {
        if (state != State.FIGHT) return;
        int r = aliveCount(Team.RED), b = aliveCount(Team.BLUE);
        if (r > 0 && b > 0) return;
        endRound(r > 0 ? Team.RED : b > 0 ? Team.BLUE : null);
    }

    private void endRound(Team winner) {
        state = State.BETWEEN;
        timer = BETWEEN_SECONDS;
        if (winner == null) {
            broadcast(Component.text("Kolo skoncilo remizou, nikdo nebodoval.", NamedTextColor.GRAY));
        } else {
            score.merge(winner, 1, Integer::sum);
            broadcast(Component.text("Kolo vyhral tym ", NamedTextColor.WHITE)
                    .append(Component.text(winner.name, winner.color))
                    .append(Component.text("  (" + score.get(Team.RED) + " : " + score.get(Team.BLUE) + ")",
                            NamedTextColor.GRAY)));
            if (score.get(winner) >= POINTS_TO_WIN) {
                gameOver = true;
                showTitle(Component.text("VYHRAL TYM " + winner.name.toUpperCase(), winner.color),
                        Component.text(score.get(Team.RED) + " : " + score.get(Team.BLUE), NamedTextColor.WHITE));
            }
        }
        updateBoard();
    }

    private void stopGame() {
        if (task != null) task.cancel();
        task = null;
        for (UUID id : new ArrayList<>(teams.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.setSpectatorTarget(null);
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            resetPlayer(p);
            teleportLobby(p);
        }
        teams.clear();
        alive.clear();
        state = State.IDLE;
        board = null;
        objective = null;
    }

    // ====== HRAC ======
    private void resetPlayer(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    private void giveKit(Player p, Team t) {
        ItemStack[] armor = {
                dyed(Material.LEATHER_BOOTS, t),
                dyed(Material.LEATHER_LEGGINGS, t),
                dyed(Material.LEATHER_CHESTPLATE, t),
                dyed(Material.LEATHER_HELMET, t)
        };
        p.getInventory().setArmorContents(armor);
        p.getInventory().setItem(0, new ItemStack(Material.STONE_SWORD));
        p.getInventory().setItem(1, new ItemStack(Material.BOW));
        p.getInventory().setItem(2, new ItemStack(Material.ARROW, 16));
    }

    private ItemStack dyed(Material m, Team t) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(t.armor);
        meta.setUnbreakable(true);
        it.setItemMeta(meta);
        return it;
    }

    private void eliminate(Player victim, Player killer) {
        alive.remove(victim.getUniqueId());
        victim.getInventory().clear();
        victim.setHealth(20.0);
        victim.setGameMode(GameMode.SPECTATOR);

        Team vt = teams.get(victim.getUniqueId());
        Component msg = Component.text(victim.getName(), vt.color);
        if (killer != null) {
            msg = msg.append(Component.text(" byl zabit hracem ", NamedTextColor.GRAY))
                    .append(Component.text(killer.getName(), teams.get(killer.getUniqueId()).color));
        } else {
            msg = msg.append(Component.text(" zemrel.", NamedTextColor.GRAY));
        }
        broadcast(msg);

        // spectate spoluhrace
        Bukkit.getScheduler().runTask(this, () -> {
            for (UUID id : alive) {
                if (teams.get(id) == vt) {
                    Player mate = Bukkit.getPlayer(id);
                    if (mate != null) { victim.setSpectatorTarget(mate); break; }
                }
            }
        });
        checkRoundEnd();
        updateBoard();
    }

    private Location getLocation(String path) {
        return getConfig().getLocation(path);
    }

    private void teleportLobby(Player p) {
        Location l = getLocation("lobby");
        if (l != null) p.teleport(l);
    }

    private void broadcast(Component c) {
        for (UUID id : teams.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(c);
        }
    }

    private void showTitle(Component main, Component sub) {
        for (UUID id : teams.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showTitle(Title.title(main, sub));
        }
    }

    // ====== SCOREBOARD ======
    private void updateBoard() {
        if (objective == null) return;

        String time = switch (state) {
            case FREEZE -> "start";
            case FIGHT -> String.format("%d:%02d", timer / 60, timer % 60);
            default -> "--";
        };
        objective.displayName(Component.text("Kolo " + round + "  |  " + time, NamedTextColor.GOLD));

        List<Component> lines = new ArrayList<>();
        for (Team t : Team.values()) {
            if (t == Team.BLUE) lines.add(Component.empty());
            lines.add(Component.text(t.name + " - " + score.getOrDefault(t, 0) + " b.", t.color)
                    .decorate(TextDecoration.BOLD));
            for (Map.Entry<UUID, Team> e : teams.entrySet()) {
                if (e.getValue() != t) continue;
                String name = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("?");
                if (alive.contains(e.getKey())) {
                    lines.add(Component.text(" " + name, NamedTextColor.WHITE));
                } else {
                    lines.add(Component.text(" " + name, NamedTextColor.DARK_GRAY)
                            .decorate(TextDecoration.STRIKETHROUGH));
                }
            }
        }

        for (String entry : new ArrayList<>(board.getEntries())) {
            if (entry.startsWith("\u00a7")) board.resetScores(entry);
        }
        for (int i = 0; i < lines.size() && i < 15; i++) {
            String entry = "\u00a7" + Integer.toHexString(i); // unikatni neviditelny zaznam
            Score s = objective.getScore(entry);
            s.setScore(15 - i);
            s.customName(lines.get(i));
        }
    }

    // ====== EVENTY ======
    private boolean inGame(Player p) {
        return state != State.IDLE && teams.containsKey(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !inGame(victim)) return;

        if (state != State.FIGHT || !alive.contains(victim.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            if (ee.getDamager() instanceof Player p) attacker = p;
            else if (ee.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        }
        if (attacker != null && attacker != victim
                && teams.get(attacker.getUniqueId()) == teams.get(victim.getUniqueId())) {
            e.setCancelled(true); // zadny friendly fire
            return;
        }

        if (e.getFinalDamage() >= victim.getHealth()) {
            e.setCancelled(true);
            eliminate(victim, attacker == victim ? null : attacker);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (state != State.FREEZE || !inGame(p)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || from.getY() != to.getY()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    @EventHandler
    public void onRegen(EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof Player p && inGame(p)
                && e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            e.setCancelled(true); // zadna prirozena regenerace
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inGame(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (inGame(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (teams.containsKey(p.getUniqueId())) removePlayer(p, false);
    }
}
