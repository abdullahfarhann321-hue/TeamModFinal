package com.anas.teammod;

import com.booksaw.betterTeams.Team;
import com.booksaw.betterTeams.customEvents.PlayerJoinTeamEvent;
import com.booksaw.betterTeams.customEvents.TeamSendMessageEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * TeamMod - single-file build. Every class is nested inside this one file
 * so the whole plugin lives in one source file (easier to manage/upload).
 * Behaviour is identical to the multi-file layout.
 */
public final class TeamMod extends JavaPlugin {


        private StorageManager storage;
        private PunishmentManager punishments;

        @Override
        public void onEnable() {
            saveDefaultConfig();

            Messages messages = new Messages();
            messages.load(getConfig());

            TeamsConfig teams = new TeamsConfig(this);
            teams.load();

            BetterTeamsHook betterTeams = new BetterTeamsHook();

            Sounds sounds = new Sounds(this);
            sounds.load();

            storage = new StorageManager(this);
            storage.load();

            punishments = new PunishmentManager(this, storage, betterTeams, messages, teams, sounds);
            WarningManager warnings = new WarningManager(this, storage, messages, punishments);
            ReportManager reports = new ReportManager(this, storage, teams, messages, sounds);

            CommandHandler handler = new CommandHandler(this, punishments, warnings, messages, teams, reports);
            PluginCommand command = getCommand("teammod");
            if (command != null) {
                command.setExecutor(handler);
                command.setTabCompleter(handler);
            } else {
                getLogger().severe("Command 'teammod' missing from plugin.yml; commands will not work.");
            }

            ReportCommand reportCommand = new ReportCommand(this, teams, reports, messages);
            PluginCommand reportCmd = getCommand("report");
            if (reportCmd != null) {
                reportCmd.setExecutor(reportCommand);
                reportCmd.setTabCompleter(reportCommand);
            } else {
                getLogger().severe("Command 'report' missing from plugin.yml; /report will not work.");
            }

            getServer().getPluginManager().registerEvents(new ChatListener(punishments, messages), this);
            getServer().getPluginManager().registerEvents(new JoinListener(punishments, messages), this);
            getServer().getPluginManager().registerEvents(new LoginListener(teams, reports), this);

            punishments.rescheduleAll();

            getLogger().info("TeamMod enabled (config-defined teams, hooked into BetterTeams).");
        }

        @Override
        public void onDisable() {
            if (punishments != null) punishments.cancelAllTasks();
            if (storage != null) storage.save();
            getLogger().info("TeamMod disabled.");
        }

    // ================= nested classes =================

    /**
     * The only place TeamMod calls into the BetterTeams API.
     *
     * <p>Team membership and leadership for ACCESS CONTROL are handled by
     * {@link TeamsConfig} (config-defined), not here. BetterTeams is used only to
     * make {@code /tm kick} actually remove the player from their in-game team.</p>
     *
     * <p>Verified against BetterTeams 5.1.2 (package {@code com.booksaw.betterTeams}):
     * {@code Team.getTeam(OfflinePlayer)} returns the player's team or null, and
     * {@code Team.removePlayer(OfflinePlayer)} removes them (returns boolean).</p>
     */
    static class BetterTeamsHook {

        /** @return the BetterTeams team the player is in, or null. */
        public Team getTeamOf(OfflinePlayer player) {
            return Team.getTeam(player);
        }

        /**
         * Removes the player from their current BetterTeams team, if any.
         * @return true if they were on a team and were removed.
         */
        public boolean removeFromTeam(OfflinePlayer player) {
            Team team = Team.getTeam(player);
            if (team == null) return false;
            return team.removePlayer(player);
        }
    }

    /**
     * Blocks muted players from ALL chat.
     *
     * <p>Two events are covered so no chat channel slips through:</p>
     * <ul>
     *   <li>{@link AsyncChatEvent} - normal/global server chat.</li>
     *   <li>{@link TeamSendMessageEvent} - BetterTeams team chat AND ally chat, which
     *       do NOT go through the normal chat event. This event extends
     *       {@code TeamPlayerEvent -> TeamEvent implements Cancellable}, so cancelling
     *       it blocks the team/ally message. (Verified against BetterTeams 5.1.2.)</li>
     * </ul>
     */
    static class ChatListener implements Listener {

        private final PunishmentManager punishments;
        private final Messages messages;

        public ChatListener(PunishmentManager punishments, Messages messages) {
            this.punishments = punishments;
            this.messages = messages;
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onChat(AsyncChatEvent event) {
            Player player = event.getPlayer();
            if (blockIfMuted(player.getUniqueId(), player)) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onTeamChat(TeamSendMessageEvent event) {
            OfflinePlayer sender = event.getPlayer();
            if (sender == null) return;
            if (blockIfMuted(sender.getUniqueId(), sender.getPlayer())) {
                event.setCancelled(true);
            }
        }

        /**
         * @return true if the player is muted (caller should cancel the event). Also
         *         notifies the player how long is left, if they are online.
         */
        private boolean blockIfMuted(UUID uuid, Player online) {
            Punishment mute = punishments.getActiveMuteByPlayer(uuid);
            if (mute == null) return false;
            if (online != null) {
                long remaining = mute.remainingMillis(System.currentTimeMillis());
                online.sendMessage(messages.prefixed("muted-blocked",
                        Messages.ph("remaining", DurationUtil.format(remaining))));
            }
            return true;
        }
    }

    /**
     * Handles {@code /teammod} (alias {@code /tm}).
     *
     * <p>Access model (all config-driven, see config.yml):</p>
     * <ul>
     *   <li>Moderation commands (kick/ban/mute/warn/unban/unmute/info/list/history):
     *       usable by anyone listed as a leader of a team in config. No op needed.
     *       A leader may only target players on their own team, never another leader
     *       (self is allowed).</li>
     *   <li>Management commands (addleader/removeleader/addmember/removemember/reload):
     *       operator-only (permission {@code teammod.admin}, default op).</li>
     * </ul>
     */
    static class CommandHandler implements CommandExecutor, TabCompleter {

        private static final String DEFAULT_REASON = "No reason provided";
        private static final DateTimeFormatter DATE_FMT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

        private static final List<String> MOD_SUBS = List.of(
                "kick", "ban", "mute", "unban", "unmute", "warn", "history", "info", "list",
                "reports", "resolve");
        private static final List<String> ADMIN_SUBS = List.of(
                "addleader", "removeleader", "addmember", "removemember", "reload");

        private final JavaPlugin plugin;
        private final PunishmentManager punishments;
        private final WarningManager warnings;
        private final Messages messages;
        private final TeamsConfig teams;
        private final ReportManager reports;

        public CommandHandler(JavaPlugin plugin, PunishmentManager punishments, WarningManager warnings,
                              Messages messages, TeamsConfig teams, ReportManager reports) {
            this.plugin = plugin;
            this.punishments = punishments;
            this.warnings = warnings;
            this.messages = messages;
            this.teams = teams;
            this.reports = reports;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player leader)) {
                sender.sendMessage(messages.prefixed("console-cannot-use"));
                return true;
            }
            if (args.length == 0) {
                reply(leader, "usage", Messages.ph("usage", "/teammod <" + String.join("|", MOD_SUBS) + "> ..."));
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);

            // --- management (operator only) --------------------------------------
            if (ADMIN_SUBS.contains(sub)) {
                if (!leader.hasPermission("teammod.admin")) {
                    reply(leader, "not-admin");
                    return true;
                }
                handleAdmin(leader, sub, args);
                return true;
            }

            // --- moderation: must be a configured team leader --------------------
            String team = teams.teamLedBy(leader.getName());
            if (team == null) {
                reply(leader, "not-leader");
                return true;
            }

            switch (sub) {
                case "kick" -> handleTimed(leader, team, args, PunishmentType.KICK);
                case "ban"  -> handleTimed(leader, team, args, PunishmentType.BAN);
                case "mute" -> handleTimed(leader, team, args, PunishmentType.MUTE);
                case "warn" -> handleWarn(leader, team, args);
                case "unban" -> handleUnban(leader, team, args);
                case "unmute" -> handleUnmute(leader, team, args);
                case "info" -> handleInfo(leader, team, args);
                case "list" -> handleList(leader, team);
                case "history" -> handleHistory(leader, args);
                case "reports" -> handleReports(leader, team);
                case "resolve" -> handleResolve(leader, team, args);
                default -> reply(leader, "usage",
                        Messages.ph("usage", "/teammod <" + String.join("|", MOD_SUBS) + "> ..."));
            }
            return true;
        }

        // =====================================================================
        //  Moderation
        // =====================================================================

        private void handleTimed(Player leader, String team, String[] args, PunishmentType type) {
            if (args.length < 2) {
                reply(leader, "usage", Messages.ph("usage", "/teammod " + type.name().toLowerCase()
                        + " <player> [duration] [reason]"));
                return;
            }
            OfflinePlayer target = resolveTarget(args[1]);
            if (target == null) {
                reply(leader, "player-not-found", Messages.ph("arg", args[1]));
                return;
            }
            if (!validateTarget(leader, team, target)) return;

            Parsed parsed = parseDurationAndReason(args, 2, defaultMillisFor(type));
            String targetName = displayName(target, args[1]);
            switch (type) {
                case KICK -> punishments.applyKick(leader, target, targetName, team, parsed.durationMillis, parsed.reason);
                case BAN  -> punishments.applyBan(leader, target, targetName, team, parsed.durationMillis, parsed.reason);
                case MUTE -> punishments.applyMute(leader, target, targetName, team, parsed.durationMillis, parsed.reason);
                default -> {}
            }
        }

        private void handleWarn(Player leader, String team, String[] args) {
            if (args.length < 2) {
                reply(leader, "usage", Messages.ph("usage", "/teammod warn <player> [reason]"));
                return;
            }
            OfflinePlayer target = resolveTarget(args[1]);
            if (target == null) {
                reply(leader, "player-not-found", Messages.ph("arg", args[1]));
                return;
            }
            if (!validateTarget(leader, team, target)) return;
            warnings.warn(leader, target, displayName(target, args[1]), team, joinReason(args, 2));
        }

        private void handleUnban(Player leader, String team, String[] args) {
            if (args.length < 2) {
                reply(leader, "usage", Messages.ph("usage", "/teammod unban <player>"));
                return;
            }
            OfflinePlayer target = resolveTarget(args[1]);
            if (target == null) {
                reply(leader, "player-not-found", Messages.ph("arg", args[1]));
                return;
            }
            String targetName = displayName(target, args[1]);
            if (!punishments.unban(leader, target, targetName, team)) {
                reply(leader, "no-active-punishment", Messages.ph("target", targetName));
            }
        }

        private void handleUnmute(Player leader, String team, String[] args) {
            if (args.length < 2) {
                reply(leader, "usage", Messages.ph("usage", "/teammod unmute <player>"));
                return;
            }
            OfflinePlayer target = resolveTarget(args[1]);
            if (target == null) {
                reply(leader, "player-not-found", Messages.ph("arg", args[1]));
                return;
            }
            String targetName = displayName(target, args[1]);
            if (!punishments.unmute(leader, target, targetName, team)) {
                reply(leader, "no-active-punishment", Messages.ph("target", targetName));
            }
        }

        private void handleInfo(Player leader, String team, String[] args) {
            if (args.length < 2) {
                reply(leader, "usage", Messages.ph("usage", "/teammod info <player>"));
                return;
            }
            OfflinePlayer target = resolveTarget(args[1]);
            if (target == null) {
                reply(leader, "player-not-found", Messages.ph("arg", args[1]));
                return;
            }
            String targetName = displayName(target, args[1]);
            leader.sendMessage(messages.get("info-header", Messages.ph("target", targetName)));
            List<Punishment> active = punishments.getActiveForTarget(team, target.getUniqueId());
            if (active.isEmpty()) {
                leader.sendMessage(messages.get("info-none"));
            } else {
                long now = System.currentTimeMillis();
                for (Punishment p : active) {
                    leader.sendMessage(messages.get("info-active", Messages.ph(
                            "type", p.getType().name(),
                            "remaining", DurationUtil.format(p.remainingMillis(now)),
                            "leader", p.getIssuedByName(),
                            "reason", p.getReason())));
                }
            }
            leader.sendMessage(messages.get("info-warnings", Messages.ph(
                    "count", String.valueOf(warnings.getCount(team, target.getUniqueId())),
                    "threshold", String.valueOf(plugin.getConfig().getInt("warn-auto-mute-threshold", 3)))));
        }

        private void handleList(Player leader, String team) {
            leader.sendMessage(messages.get("list-header", Messages.ph("team", team)));
            List<Punishment> active = punishments.getActiveForTeam(team);
            if (active.isEmpty()) {
                leader.sendMessage(messages.get("list-empty"));
                return;
            }
            long now = System.currentTimeMillis();
            for (Punishment p : active) {
                leader.sendMessage(messages.get("list-entry", Messages.ph(
                        "type", p.getType().name(),
                        "target", p.getTargetName(),
                        "remaining", DurationUtil.format(p.remainingMillis(now)),
                        "leader", p.getIssuedByName())));
            }
        }

        private void handleHistory(Player leader, String[] args) {
            if (args.length < 2) {
                reply(leader, "usage", Messages.ph("usage", "/teammod history <playername>"));
                return;
            }
            OfflinePlayer target = resolveTarget(args[1]);
            if (target == null) {
                reply(leader, "player-not-found", Messages.ph("arg", args[1]));
                return;
            }
            String targetName = displayName(target, args[1]);
            List<Punishment> history = punishments.getFullHistory(target.getUniqueId());
            leader.sendMessage(messages.get("history-header", Messages.ph("target", targetName)));
            if (history.isEmpty()) {
                leader.sendMessage(messages.get("history-empty", Messages.ph("target", targetName)));
                return;
            }
            int index = 1;
            for (Punishment p : history) {
                String duration = (p.getExpiresAt() < 0) ? "-"
                        : DurationUtil.format(p.getExpiresAt() - p.getIssuedAt());
                leader.sendMessage(messages.get("history-entry", Messages.ph(
                        "index", String.valueOf(index++),
                        "type", p.getType().name() + (p.isAuto() ? " (auto)" : ""),
                        "duration", duration,
                        "leader", p.getIssuedByName(),
                        "date", DATE_FMT.format(Instant.ofEpochMilli(p.getIssuedAt())),
                        "status", p.getOutcome().name(),
                        "reason", p.getReason())));
            }
        }

        private void handleReports(Player leader, String team) {
            List<Report> list = reports.list(team);
            leader.sendMessage(messages.get("reports-header", Messages.ph("team", team)));
            if (list.isEmpty()) {
                leader.sendMessage(messages.get("reports-empty"));
                return;
            }
            int index = 1;
            for (Report r : list) {
                leader.sendMessage(messages.get("reports-entry", Messages.ph(
                        "index", String.valueOf(index++),
                        "reporter", r.getReporter(),
                        "reported", r.getReported(),
                        "time", r.getEstimatedTime(),
                        "date", DATE_FMT.format(Instant.ofEpochMilli(r.getTimestamp())),
                        "reason", r.getReason())));
            }
            leader.sendMessage(messages.get("reports-footer"));
        }

        private void handleResolve(Player leader, String team, String[] args) {
            if (args.length < 2) {
                reply(leader, "usage", Messages.ph("usage", "/teammod resolve <number>"));
                return;
            }
            int index;
            try {
                index = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                reply(leader, "report-resolve-badindex");
                return;
            }
            String reported = reports.resolve(team, index);
            if (reported == null) {
                reply(leader, "report-resolve-badindex");
            } else {
                reply(leader, "report-resolved", Messages.ph("reported", reported));
            }
        }

        // =====================================================================
        //  Management (operator only)
        // =====================================================================

        private void handleAdmin(Player admin, String sub, String[] args) {
            if (sub.equals("reload")) {
                plugin.reloadConfig();
                teams.load();
                messages.load(plugin.getConfig());
                punishments.reloadSounds();
                reply(admin, "reload-done");
                return;
            }
            // add/remove leader/member: <team> <player>
            if (args.length < 3) {
                reply(admin, "usage", Messages.ph("usage", "/teammod " + sub + " <team> <player>"));
                return;
            }
            String team = args[1];
            String player = args[2];
            if (!teams.hasTeam(team)) {
                reply(admin, "team-unknown", Messages.ph("team", team, "teams", String.join(", ", teams.teamNames())));
                return;
            }
            switch (sub) {
                case "addleader" -> report(admin, teams.addLeader(team, player), team, player, "leader-added", "entry-exists");
                case "removeleader" -> report(admin, teams.removeLeader(team, player), team, player, "leader-removed", "entry-missing");
                case "addmember" -> report(admin, teams.addMember(team, player), team, player, "member-added", "entry-exists");
                case "removemember" -> report(admin, teams.removeMember(team, player), team, player, "member-removed", "entry-missing");
                default -> reply(admin, "usage", Messages.ph("usage", "/teammod " + sub + " <team> <player>"));
            }
        }

        private void report(Player admin, boolean ok, String team, String player, String okKey, String failKey) {
            if (ok) reply(admin, okKey, Messages.ph("team", team, "player", player));
            else reply(admin, failKey, Messages.ph("team", team, "player", player));
        }

        // =====================================================================
        //  Validation / parsing
        // =====================================================================

        private boolean validateTarget(Player leader, String team, OfflinePlayer target) {
            String targetName = displayName(target, target.getName());
            boolean self = leader.getName().equalsIgnoreCase(targetName);
            if (self) return true; // self-punishment allowed
            if (!teams.isOnTeam(team, targetName)) {
                reply(leader, "target-not-on-team", Messages.ph("target", targetName));
                return false;
            }
            if (teams.isLeaderOf(team, targetName)) {
                reply(leader, "cannot-target-leader");
                return false;
            }
            return true;
        }

        private record Parsed(long durationMillis, String reason) {}

        private Parsed parseDurationAndReason(String[] args, int start, long defaultMillis) {
            long duration = defaultMillis;
            int reasonStart = start;
            if (args.length > start && DurationUtil.looksLikeDuration(args[start])) {
                duration = DurationUtil.parseMillis(args[start]);
                reasonStart = start + 1;
            }
            return new Parsed(duration, joinReason(args, reasonStart));
        }

        private String joinReason(String[] args, int start) {
            if (args.length <= start) return DEFAULT_REASON;
            return String.join(" ", Arrays.copyOfRange(args, start, args.length));
        }

        private long defaultMillisFor(PunishmentType type) {
            return switch (type) {
                case KICK -> DurationUtil.minutesToMillis(plugin.getConfig().getLong("default-kick-minutes", 5));
                case BAN  -> DurationUtil.minutesToMillis(plugin.getConfig().getLong("default-ban-minutes", 30));
                case MUTE -> DurationUtil.minutesToMillis(plugin.getConfig().getLong("default-mute-minutes", 30));
                default -> 0L;
            };
        }

        /** Resolves a name without a blocking web lookup; null if never seen. */
        private OfflinePlayer resolveTarget(String name) {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) return online;
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            if (cached != null) return cached;
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            return op.hasPlayedBefore() ? op : null;
        }

        private String displayName(OfflinePlayer player, String fallback) {
            String n = player.getName();
            return (n != null && !n.isBlank()) ? n : fallback;
        }

        private void reply(CommandSender sender, String key) {
            sender.sendMessage(messages.prefixed(key));
        }

        private void reply(CommandSender sender, String key, java.util.Map<String, String> placeholders) {
            sender.sendMessage(messages.prefixed(key, placeholders));
        }

        // =====================================================================
        //  Tab completion
        // =====================================================================

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!(sender instanceof Player player)) return List.of();

            if (args.length == 1) {
                List<String> subs = new ArrayList<>(MOD_SUBS);
                if (player.hasPermission("teammod.admin")) subs.addAll(ADMIN_SUBS);
                return filter(subs, args[0]);
            }

            String sub = args[0].toLowerCase(Locale.ROOT);

            if (args.length == 2) {
                if (ADMIN_SUBS.contains(sub) && !sub.equals("reload")) {
                    return filter(new ArrayList<>(teams.teamNames()), args[1]);
                }
                if (MOD_SUBS.contains(sub) && !sub.equals("list")) {
                    List<String> names = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
                    return filter(names, args[1]);
                }
            }

            if (args.length == 3 && (sub.equals("kick") || sub.equals("ban") || sub.equals("mute"))) {
                return filter(List.of("5m", "10m", "30m", "1h", "1d"), args[2]);
            }
            if (args.length == 3 && ADMIN_SUBS.contains(sub) && !sub.equals("reload")) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
                return filter(names, args[2]);
            }
            return List.of();
        }

        private List<String> filter(List<String> options, String prefix) {
            String p = prefix.toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
            return out;
        }
    }

    /**
     * Parses and formats human-friendly durations such as {@code 30m}, {@code 1h30m},
     * {@code 2d}, or a bare number (interpreted as minutes).
     */
    static final class DurationUtil {

        private DurationUtil() {}

        // Matches one or more <number><unit> groups, e.g. "1h30m", "45s", "2d".
        private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([smhd])", Pattern.CASE_INSENSITIVE);
        // Matches a bare integer (treated as minutes).
        private static final Pattern BARE_MINUTES = Pattern.compile("^\\d+$");

        /**
         * Parses a duration string into milliseconds.
         *
         * @return milliseconds, or -1 if the string is not a valid duration (so the
         *         caller can treat it as the start of a reason instead).
         */
        public static long parseMillis(String input) {
            if (input == null || input.isBlank()) return -1;
            String s = input.trim();

            if (BARE_MINUTES.matcher(s).matches()) {
                try {
                    return Long.parseLong(s) * 60_000L; // bare number = minutes
                } catch (NumberFormatException e) {
                    return -1;
                }
            }

            Matcher m = TOKEN.matcher(s);
            long total = 0;
            int matchedChars = 0;
            while (m.find()) {
                long value = Long.parseLong(m.group(1));
                char unit = Character.toLowerCase(m.group(2).charAt(0));
                total += switch (unit) {
                    case 's' -> value * 1_000L;
                    case 'm' -> value * 60_000L;
                    case 'h' -> value * 3_600_000L;
                    case 'd' -> value * 86_400_000L;
                    default -> 0L;
                };
                matchedChars += m.group().length();
            }
            // Only accept if the WHOLE string was duration tokens (ignoring spaces),
            // otherwise it is probably a reason word like "griefing".
            if (total > 0 && matchedChars == s.replace(" ", "").length()) {
                return total;
            }
            return -1;
        }

        /** True if the token looks like a duration (used when splitting args). */
        public static boolean looksLikeDuration(String token) {
            return parseMillis(token) > 0;
        }

        /** Formats milliseconds into a compact string like "1h30m" or "45s". */
        public static String format(long millis) {
            if (millis <= 0) return "0s";
            long seconds = millis / 1000;
            long days = seconds / 86_400; seconds %= 86_400;
            long hours = seconds / 3_600; seconds %= 3_600;
            long minutes = seconds / 60;  seconds %= 60;

            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append('d');
            if (hours > 0) sb.append(hours).append('h');
            if (minutes > 0) sb.append(minutes).append('m');
            if (seconds > 0 && sb.length() == 0) sb.append(seconds).append('s'); // only show seconds for short spans
            if (sb.length() == 0) sb.append("0s");
            return sb.toString();
        }

        /** Convenience for turning minutes (from config defaults) into millis. */
        public static long minutesToMillis(long minutes) {
            return minutes * 60_000L;
        }
    }

    /**
     * Blocks a kicked player from rejoining a BetterTeams team before their kick
     * expires. (Bans are server-level and handled by Bukkit's ban list, so they do
     * not need this listener.)
     *
     * <p>BetterTeams 5.1.2: {@code PlayerJoinTeamEvent} extends
     * {@code TeamPlayerEvent -> TeamEvent}, and {@code TeamEvent implements
     * Cancellable}, so cancelling here prevents the join. {@code getPlayer()} returns
     * the joining {@link OfflinePlayer}.</p>
     */
    static class JoinListener implements Listener {

        private final PunishmentManager punishments;
        private final Messages messages;

        public JoinListener(PunishmentManager punishments, Messages messages) {
            this.punishments = punishments;
            this.messages = messages;
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onJoinTeam(PlayerJoinTeamEvent event) {
            OfflinePlayer joiner = event.getPlayer();
            if (joiner == null) return;

            Punishment block = punishments.getActiveKickByPlayer(joiner.getUniqueId());
            if (block == null) return;

            event.setCancelled(true);
            long remaining = block.remainingMillis(System.currentTimeMillis());
            if (joiner.getPlayer() != null) {
                joiner.getPlayer().sendMessage(messages.prefixed("join-blocked",
                        Messages.ph("remaining", DurationUtil.format(remaining))));
            }
        }
    }

    /**
     * When a team leader logs in, notifies them (message + sound) if their team has
     * open reports waiting, so reports filed while they were offline are never missed.
     */
    static class LoginListener implements Listener {

        private final TeamsConfig teams;
        private final ReportManager reports;

        public LoginListener(TeamsConfig teams, ReportManager reports) {
            this.teams = teams;
            this.reports = reports;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            String team = teams.teamLedBy(player.getName()); // only leaders get notified
            if (team == null) return;
            reports.notifyOpenReports(player, team);
        }
    }

    /**
     * Loads the customizable message strings from config.yml and renders them into
     * Adventure {@link Component}s.
     *
     * <p>By default strings are parsed as MiniMessage (modern Paper). If
     * {@code use-legacy-colors: true} is set, they are parsed with the legacy '&amp;'
     * colour-code serializer instead, so server owners who prefer legacy codes are
     * still supported (see the comment block in config.yml).</p>
     *
     * <p>Placeholders are written {@code <name>} in both modes. In MiniMessage mode
     * they are inserted via {@link Placeholder#unparsed} (values are NOT re-parsed,
     * so a reason cannot inject formatting). In legacy mode they are substituted
     * textually before deserialization.</p>
     */
    static class Messages {

        private final MiniMessage mm = MiniMessage.miniMessage();
        private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

        private final Map<String, String> strings = new HashMap<>();
        private boolean useLegacy;
        private Component prefix = Component.empty();

        /** Reloads all message strings from the given config. */
        public void load(FileConfiguration config) {
            strings.clear();
            useLegacy = config.getBoolean("use-legacy-colors", false);
            if (config.isConfigurationSection("messages")) {
                var section = config.getConfigurationSection("messages");
                for (String key : section.getKeys(false)) {
                    strings.put(key, section.getString(key, ""));
                }
            }
            prefix = render(strings.getOrDefault("prefix", ""), Map.of());
        }

        /** Renders a message by key with {@code <placeholder>} substitutions. */
        public Component get(String key, Map<String, String> placeholders) {
            String template = strings.get(key);
            if (template == null) {
                // Defensive fallback so a missing key never crashes a command.
                return Component.text("[missing message: " + key + "]");
            }
            return render(template, placeholders == null ? Map.of() : placeholders);
        }

        public Component get(String key) {
            return get(key, Map.of());
        }

        /** Same as {@link #get} but with the configured prefix prepended. */
        public Component prefixed(String key, Map<String, String> placeholders) {
            return prefix.append(get(key, placeholders));
        }

        public Component prefixed(String key) {
            return prefixed(key, Map.of());
        }

        private Component render(String template, Map<String, String> placeholders) {
            if (template == null || template.isEmpty()) return Component.empty();
            if (useLegacy) {
                String out = template;
                for (Map.Entry<String, String> e : placeholders.entrySet()) {
                    out = out.replace("<" + e.getKey() + ">", e.getValue() == null ? "" : e.getValue());
                }
                return legacy.deserialize(out);
            }
            TagResolver.Builder builder = TagResolver.builder();
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                builder.resolver(Placeholder.unparsed(e.getKey(), e.getValue() == null ? "" : e.getValue()));
            }
            return mm.deserialize(template, builder.build());
        }

        /** Small helper to build a placeholder map fluently: {@code ph("target", name, ...)}. */
        public static Map<String, String> ph(Object... kv) {
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                map.put(String.valueOf(kv[i]), kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
            }
            return map;
        }
    }

    /**
     * A single moderation record (active or historical).
     *
     * <p>{@code team} is the TeamMod config team name (e.g. "team1"), since teams are
     * now config-defined rather than read from BetterTeams.</p>
     */
    static class Punishment {

        public enum Outcome { ACTIVE, EXPIRED, LIFTED, LOGGED }

        private final String id;
        private final PunishmentType type;
        private final UUID targetUuid;
        private final String targetName;
        private final String team;          // config team name
        private final UUID issuedByUuid;
        private final String issuedByName;
        private final String reason;
        private final long issuedAt;
        private final long expiresAt;       // epoch millis; -1 = no timed expiry (warns)
        private final boolean auto;

        private Outcome outcome;
        private long endedAt;

        public Punishment(String id, PunishmentType type, UUID targetUuid, String targetName,
                          String team, UUID issuedByUuid, String issuedByName, String reason,
                          long issuedAt, long expiresAt, boolean auto, Outcome outcome, long endedAt) {
            this.id = id;
            this.type = type;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.team = team;
            this.issuedByUuid = issuedByUuid;
            this.issuedByName = issuedByName;
            this.reason = reason;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.auto = auto;
            this.outcome = outcome;
            this.endedAt = endedAt;
        }

        public String getId() { return id; }
        public PunishmentType getType() { return type; }
        public UUID getTargetUuid() { return targetUuid; }
        public String getTargetName() { return targetName; }
        public String getTeam() { return team; }
        public UUID getIssuedByUuid() { return issuedByUuid; }
        public String getIssuedByName() { return issuedByName; }
        public String getReason() { return reason; }
        public long getIssuedAt() { return issuedAt; }
        public long getExpiresAt() { return expiresAt; }
        public boolean isAuto() { return auto; }
        public Outcome getOutcome() { return outcome; }
        public long getEndedAt() { return endedAt; }

        public boolean isExpired(long now) {
            return expiresAt >= 0 && now >= expiresAt;
        }

        public long remainingMillis(long now) {
            if (expiresAt < 0) return Long.MAX_VALUE;
            return Math.max(0L, expiresAt - now);
        }

        public void markEnded(Outcome outcome, long endedAt) {
            this.outcome = outcome;
            this.endedAt = endedAt;
        }

        public void save(ConfigurationSection section) {
            section.set("type", type.name());
            section.set("target", targetUuid.toString());
            section.set("targetName", targetName);
            section.set("team", team);
            section.set("by", issuedByUuid != null ? issuedByUuid.toString() : null);
            section.set("byName", issuedByName);
            section.set("reason", reason);
            section.set("issuedAt", issuedAt);
            section.set("expiresAt", expiresAt);
            section.set("auto", auto);
            section.set("outcome", outcome.name());
            section.set("endedAt", endedAt);
        }

        public static Punishment load(String id, ConfigurationSection s) {
            try {
                PunishmentType type = PunishmentType.valueOf(s.getString("type"));
                UUID target = UUID.fromString(s.getString("target"));
                String targetName = s.getString("targetName", "unknown");
                String team = s.getString("team", "unknown");
                String byStr = s.getString("by");
                UUID by = byStr != null ? UUID.fromString(byStr) : null;
                String byName = s.getString("byName", "unknown");
                String reason = s.getString("reason", "");
                long issuedAt = s.getLong("issuedAt");
                long expiresAt = s.getLong("expiresAt", -1L);
                boolean auto = s.getBoolean("auto", false);
                Outcome outcome = Outcome.valueOf(s.getString("outcome", Outcome.ACTIVE.name()));
                long endedAt = s.getLong("endedAt", 0L);
                return new Punishment(id, type, target, targetName, team, by, byName, reason,
                        issuedAt, expiresAt, auto, outcome, endedAt);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * Applies, lifts and auto-expires punishments, and answers the read queries used
     * by info/list/history.
     *
     * <ul>
     *   <li>KICK  - removes the target from their BetterTeams team ({@link BetterTeamsHook})
     *       and records a block that {@link JoinListener} enforces.</li>
     *   <li>BAN   - a temporary <b>server</b> ban via Bukkit's ban list, plus kicking
     *       them off if online.</li>
     *   <li>MUTE  - records a mute that {@link ChatListener} enforces on ALL chat.</li>
     * </ul>
     *
     * "Team" here is the config team name; broadcasts go to online players who are on
     * that config team (per {@link TeamsConfig}).
     */
    static class PunishmentManager {

        public static final UUID SYSTEM_UUID = new UUID(0L, 0L);
        public static final String SYSTEM_NAME = "TeamMod";

        private final JavaPlugin plugin;
        private final StorageManager storage;
        private final BetterTeamsHook betterTeams;
        private final Messages messages;
        private final TeamsConfig teams;
        private final Sounds sounds;

        private final Map<String, BukkitTask> tasks = new HashMap<>();

        public PunishmentManager(JavaPlugin plugin, StorageManager storage, BetterTeamsHook betterTeams,
                                 Messages messages, TeamsConfig teams, Sounds sounds) {
            this.plugin = plugin;
            this.storage = storage;
            this.betterTeams = betterTeams;
            this.messages = messages;
            this.teams = teams;
            this.sounds = sounds;
        }

        /** Reloads sound definitions from config (used by /tm reload). */
        public void reloadSounds() {
            sounds.load();
        }

        /** Plays the configured sound for an action to every online member of the team. */
        public void playActionSound(String team, String type) {
            Sound sound = sounds.get(type);
            if (sound == null) return;
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (teams.isOnTeam(team, online.getName())) {
                    online.playSound(sound);
                }
            }
        }

        // =====================================================================
        //  KICK  (team-only)
        // =====================================================================

        public void applyKick(Player leader, OfflinePlayer target, String targetName, String team,
                              long durationMillis, String reason) {
            long now = System.currentTimeMillis();
            Punishment p = newPunishment(PunishmentType.KICK, target, targetName, team,
                    leader.getUniqueId(), leader.getName(), reason, now, now + durationMillis, false);
            storage.addActive(p);
            scheduleExpiry(p);

            // BetterTeams' Team.removePlayer(...) fires PlayerLeaveTeamEvent, which
            // BetterTeams declares as an ASYNC event. Firing it from the main thread
            // makes Paper throw "PlayerLeaveTeamEvent may only be triggered
            // asynchronously", so the actual removal MUST run off the main thread.
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean removed = betterTeams.removeFromTeam(target);
                if (!removed) {
                    plugin.getLogger().info("[TeamMod] " + targetName
                            + " was not on a BetterTeams team when kicked; block still recorded.");
                }
            });

            String dur = DurationUtil.format(durationMillis);
            broadcastToTeam(team, messages.get("kick-broadcast",
                    Messages.ph("target", targetName, "duration", dur, "leader", leader.getName(), "reason", reason)));
            playActionSound(team, "kick");
            dm(target, messages.prefixed("kick-target",
                    Messages.ph("duration", dur, "leader", leader.getName(), "reason", reason)));
            plugin.getLogger().info("[TeamMod] " + leader.getName() + " KICK " + targetName
                    + " (team " + team + ") for " + dur + ". Reason: " + reason);
        }

        // =====================================================================
        //  BAN  (temporary SERVER ban)
        // =====================================================================

        public void applyBan(Player leader, OfflinePlayer target, String targetName, String team,
                             long durationMillis, String reason) {
            long now = System.currentTimeMillis();
            long expiresAt = now + durationMillis;
            Punishment p = newPunishment(PunishmentType.BAN, target, targetName, team,
                    leader.getUniqueId(), leader.getName(), reason, now, expiresAt, false);
            storage.addActive(p);

            String dur = DurationUtil.format(durationMillis);

            // Add a timed entry to Bukkit's ban list so the server enforces it (and
            // auto-lifts it when the date passes, even across restarts).
            addServerBan(targetName, reason, new Date(expiresAt));

            // Kick them off now if they are online, showing the ban screen.
            Player online = target.getPlayer();
            if (online != null) {
                online.kick(messages.get("ban-screen", Messages.ph("remaining", dur, "reason", reason)));
            }

            scheduleExpiry(p);
            broadcastToTeam(team, messages.get("ban-broadcast",
                    Messages.ph("target", targetName, "duration", dur, "leader", leader.getName(), "reason", reason)));
            playActionSound(team, "ban");
            plugin.getLogger().info("[TeamMod] " + leader.getName() + " SERVER-BAN " + targetName
                    + " (team " + team + ") for " + dur + ". Reason: " + reason);
        }

        @SuppressWarnings("deprecation") // BanList.Type.NAME is the simplest cross-version approach
        private void addServerBan(String name, String reason, Date expires) {
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expires, SYSTEM_NAME);
        }

        @SuppressWarnings("deprecation")
        private void removeServerBan(String name) {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
        }

        // =====================================================================
        //  MUTE  (all chat)
        // =====================================================================

        public void applyMute(Player leader, OfflinePlayer target, String targetName, String team,
                              long durationMillis, String reason) {
            applyMuteInternal(leader.getUniqueId(), leader.getName(), target, targetName, team,
                    durationMillis, reason, false);
        }

        public void applyAutoMute(OfflinePlayer target, String targetName, String team) {
            long durationMillis = DurationUtil.minutesToMillis(
                    plugin.getConfig().getLong("warn-auto-mute-minutes", 10));
            applyMuteInternal(SYSTEM_UUID, SYSTEM_NAME, target, targetName, team, durationMillis,
                    "Automatic mute: reached " + warnThreshold() + " warnings", true);
        }

        private void applyMuteInternal(UUID byUuid, String byName, OfflinePlayer target, String targetName,
                                       String team, long durationMillis, String reason, boolean auto) {
            long now = System.currentTimeMillis();

            Punishment existing = storage.findActive(team, target.getUniqueId(), PunishmentType.MUTE);
            if (existing != null) {
                cancelTask(existing.getId());
                storage.removeActive(existing.getId());
                existing.markEnded(Punishment.Outcome.LIFTED, now);
                storage.addHistory(existing);
            }

            Punishment p = newPunishment(PunishmentType.MUTE, target, targetName, team,
                    byUuid, byName, reason, now, now + durationMillis, auto);
            storage.addActive(p);
            scheduleExpiry(p);

            String dur = DurationUtil.format(durationMillis);
            if (auto) {
                broadcastToTeam(team, messages.get("auto-mute-broadcast",
                        Messages.ph("target", targetName, "duration", dur, "threshold", String.valueOf(warnThreshold()))));
                dm(target, messages.prefixed("auto-mute-target",
                        Messages.ph("duration", dur, "threshold", String.valueOf(warnThreshold()))));
                playActionSound(team, "auto-mute");
                plugin.getLogger().info("[TeamMod] AUTO-MUTE " + targetName + " (team " + team + ") for " + dur + ".");
            } else {
                broadcastToTeam(team, messages.get("mute-broadcast",
                        Messages.ph("target", targetName, "duration", dur, "leader", byName, "reason", reason)));
                dm(target, messages.prefixed("mute-target",
                        Messages.ph("duration", dur, "leader", byName, "reason", reason)));
                playActionSound(team, "mute");
                plugin.getLogger().info("[TeamMod] " + byName + " MUTE " + targetName + " (team " + team
                        + ") for " + dur + ". Reason: " + reason);
            }
        }

        // =====================================================================
        //  Manual lifts
        // =====================================================================

        /** Lifts an active server ban early. */
        public boolean unban(Player leader, OfflinePlayer target, String targetName, String team) {
            Punishment p = storage.findActive(team, target.getUniqueId(), PunishmentType.BAN);
            if (p == null) return false;
            cancelTask(p.getId());
            storage.removeActive(p.getId());
            removeServerBan(targetName);
            p.markEnded(Punishment.Outcome.LIFTED, System.currentTimeMillis());
            storage.addHistory(p);

            broadcastToTeam(team, messages.get("unban-broadcast",
                    Messages.ph("target", targetName, "leader", leader.getName())));
            playActionSound(team, "unban");
            plugin.getLogger().info("[TeamMod] " + leader.getName() + " lifted server ban on " + targetName + ".");
            return true;
        }

        /** Lifts an active mute early. */
        public boolean unmute(Player leader, OfflinePlayer target, String targetName, String team) {
            Punishment p = storage.findActive(team, target.getUniqueId(), PunishmentType.MUTE);
            if (p == null) return false;
            cancelTask(p.getId());
            storage.removeActive(p.getId());
            p.markEnded(Punishment.Outcome.LIFTED, System.currentTimeMillis());
            storage.addHistory(p);

            broadcastToTeam(team, messages.get("unmute-broadcast",
                    Messages.ph("target", targetName, "leader", leader.getName())));
            playActionSound(team, "unmute");
            dm(target, messages.prefixed("unmute-target"));
            plugin.getLogger().info("[TeamMod] " + leader.getName() + " lifted mute on " + targetName + ".");
            return true;
        }

        // =====================================================================
        //  Expiry + scheduling
        // =====================================================================

        private void scheduleExpiry(Punishment p) {
            long now = System.currentTimeMillis();
            if (p.isExpired(now)) {
                expire(p);
                return;
            }
            long ticks = Math.max(1L, p.remainingMillis(now) / 50L);
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(p), ticks);
            tasks.put(p.getId(), task);
        }

        private void expire(Punishment p) {
            if (storage.getActiveById(p.getId()) == null) {
                tasks.remove(p.getId());
                return;
            }
            tasks.remove(p.getId());
            storage.removeActive(p.getId());
            p.markEnded(Punishment.Outcome.EXPIRED, System.currentTimeMillis());
            storage.addHistory(p);

            if (p.getType() == PunishmentType.BAN) {
                removeServerBan(p.getTargetName()); // ensure the ban-list entry is gone
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(p.getTargetUuid());
            String key = switch (p.getType()) {
                case KICK -> "expiry-kick-target";
                case MUTE -> "expiry-mute-target";
                default -> null; // banned players are offline; nothing to DM
            };
            if (key != null) dm(target, messages.prefixed(key));
            plugin.getLogger().info("[TeamMod] " + p.getType() + " on " + p.getTargetName() + " expired.");
        }

        public void rescheduleAll() {
            for (Punishment p : storage.getActive()) {
                scheduleExpiry(p);
            }
        }

        public void cancelAllTasks() {
            for (BukkitTask t : tasks.values()) t.cancel();
            tasks.clear();
        }

        private void cancelTask(String id) {
            BukkitTask t = tasks.remove(id);
            if (t != null) t.cancel();
        }

        // =====================================================================
        //  Enforcement queries (listeners)
        // =====================================================================

        /** Active mute for this player on any team, or null. */
        public Punishment getActiveMuteByPlayer(UUID playerUuid) {
            return storage.findActiveByPlayer(playerUuid, PunishmentType.MUTE);
        }

        /** Active team-kick block for this player on any team, or null. */
        public Punishment getActiveKickByPlayer(UUID playerUuid) {
            return storage.findActiveByPlayer(playerUuid, PunishmentType.KICK);
        }

        // =====================================================================
        //  Read queries (info/list/history)
        // =====================================================================

        public List<Punishment> getActiveForTeam(String team) {
            List<Punishment> list = storage.getActiveForTeam(team);
            list.sort(Comparator.comparingLong(Punishment::getExpiresAt));
            return list;
        }

        public List<Punishment> getActiveForTarget(String team, UUID targetUuid) {
            List<Punishment> out = new ArrayList<>();
            for (Punishment p : storage.getActiveForTeam(team)) {
                if (targetUuid.equals(p.getTargetUuid())) out.add(p);
            }
            return out;
        }

        public List<Punishment> getFullHistory(UUID targetUuid) {
            List<Punishment> out = new ArrayList<>();
            for (Punishment p : storage.getActive()) {
                if (targetUuid.equals(p.getTargetUuid())) out.add(p);
            }
            out.addAll(storage.getHistoryForTarget(targetUuid));
            out.sort(Comparator.comparingLong(Punishment::getIssuedAt));
            return out;
        }

        // =====================================================================
        //  Helpers
        // =====================================================================

        /** Sends a component to every ONLINE player who is on the given config team. */
        public void broadcastToTeam(String team, Component message) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (teams.isOnTeam(team, online.getName())) {
                    online.sendMessage(message);
                }
            }
        }

        private Punishment newPunishment(PunishmentType type, OfflinePlayer target, String targetName,
                                         String team, UUID byUuid, String byName, String reason,
                                         long issuedAt, long expiresAt, boolean auto) {
            Punishment.Outcome outcome = (type == PunishmentType.WARN)
                    ? Punishment.Outcome.LOGGED : Punishment.Outcome.ACTIVE;
            return new Punishment(UUID.randomUUID().toString(), type, target.getUniqueId(), targetName,
                    team, byUuid, byName, reason, issuedAt, expiresAt, auto, outcome, 0L);
        }

        private int warnThreshold() {
            return plugin.getConfig().getInt("warn-auto-mute-threshold", 3);
        }

        private void dm(OfflinePlayer target, Component message) {
            Player online = target.getPlayer();
            if (online != null) online.sendMessage(message);
        }
    }

    /**
     * The kinds of moderation action TeamMod can record.
     *
     * <ul>
     *   <li>{@link #KICK} - remove the player from their BetterTeams team and block
     *       them from rejoining THAT team until it expires. Team-only.</li>
     *   <li>{@link #BAN} - a temporary <b>server</b> ban (via Bukkit's ban list).
     *       Unrelated to teams; the player cannot join the server until it expires.</li>
     *   <li>{@link #MUTE} - block the player from sending ANY chat message (not just
     *       team chat) until it expires.</li>
     *   <li>{@link #WARN} - a logged warning; no timed effect on its own.</li>
     * </ul>
     */
    static enum PunishmentType {
        KICK,
        BAN,
        MUTE,
        WARN;

        /** KICK blocks rejoining the BetterTeams team; nothing else does. */
        public boolean blocksTeamRejoin() {
            return this == KICK;
        }

        /** BAN is enforced as a server ban rather than by TeamMod listeners. */
        public boolean isServerBan() {
            return this == BAN;
        }

        /** MUTE blocks all chat. */
        public boolean blocksChat() {
            return this == MUTE;
        }
    }

    /**
     * A player-submitted report about a teammate. Informational only: it notifies
     * the team's leaders and is stored so leaders can review it later. The
     * "estimated time" is free-form text supplied by the reporter and is not
     * enforced as a punishment.
     */
    static class Report {

        private final String id;
        private final String reporter;
        private final String reported;
        private final String team;
        private final String reason;
        private final String estimatedTime;
        private final long timestamp;

        public Report(String id, String reporter, String reported, String team,
                      String reason, String estimatedTime, long timestamp) {
            this.id = id;
            this.reporter = reporter;
            this.reported = reported;
            this.team = team;
            this.reason = reason;
            this.estimatedTime = estimatedTime;
            this.timestamp = timestamp;
        }

        public String getId() { return id; }
        public String getReporter() { return reporter; }
        public String getReported() { return reported; }
        public String getTeam() { return team; }
        public String getReason() { return reason; }
        public String getEstimatedTime() { return estimatedTime; }
        public long getTimestamp() { return timestamp; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("reporter", reporter);
            m.put("reported", reported);
            m.put("team", team);
            m.put("reason", reason);
            m.put("time", estimatedTime);
            m.put("timestamp", timestamp);
            return m;
        }

        public static Report fromSection(ConfigurationSection s) {
            try {
                return new Report(
                        s.getString("id"),
                        s.getString("reporter", "unknown"),
                        s.getString("reported", "unknown"),
                        s.getString("team", "unknown"),
                        s.getString("reason", ""),
                        s.getString("time", ""),
                        s.getLong("timestamp"));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * {@code /report <player> <reason> <estimated time>} - lets a team member report
     * a player ON THEIR OWN TEAM to the team's leaders.
     *
     * <p>Parsing: the FIRST argument is the reported player, the LAST argument is the
     * (free-form) estimated time, and everything in between is the reason.</p>
     */
    static class ReportCommand implements CommandExecutor, TabCompleter {

        private final JavaPlugin plugin;
        private final TeamsConfig teams;
        private final ReportManager reports;
        private final Messages messages;

        public ReportCommand(JavaPlugin plugin, TeamsConfig teams, ReportManager reports, Messages messages) {
            this.plugin = plugin;
            this.teams = teams;
            this.reports = reports;
            this.messages = messages;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player reporter)) {
                sender.sendMessage(messages.prefixed("console-cannot-use"));
                return true;
            }
            // Need at least: <player> <reason> <time>
            if (args.length < 3) {
                reporter.sendMessage(messages.prefixed("report-usage"));
                return true;
            }

            String reporterTeam = teams.teamOf(reporter.getName());
            if (reporterTeam == null) {
                reporter.sendMessage(messages.prefixed("report-not-on-team"));
                return true;
            }

            // Nicely-cased name if the target is online, else what was typed.
            Player onlineTarget = Bukkit.getPlayerExact(args[0]);
            String reportedName = onlineTarget != null ? onlineTarget.getName() : args[0];

            if (reporter.getName().equalsIgnoreCase(reportedName)) {
                reporter.sendMessage(messages.prefixed("report-cannot-self"));
                return true;
            }
            // Same-team rule: you can only report someone on YOUR team.
            if (!teams.isOnTeam(reporterTeam, reportedName)) {
                reporter.sendMessage(messages.prefixed("report-target-not-on-team",
                        Messages.ph("target", reportedName)));
                return true;
            }

            String estimatedTime = args[args.length - 1];
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));

            reports.file(reporter, reportedName, reporterTeam, reason, estimatedTime);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!(sender instanceof Player player)) return List.of();
            if (args.length == 1) {
                String team = teams.teamOf(player.getName());
                List<String> names = new ArrayList<>();
                if (team != null) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!online.getName().equalsIgnoreCase(player.getName())
                                && teams.isOnTeam(team, online.getName())) {
                            names.add(online.getName());
                        }
                    }
                }
                String prefix = args[0].toLowerCase(Locale.ROOT);
                names.removeIf(n -> !n.toLowerCase(Locale.ROOT).startsWith(prefix));
                return names;
            }
            return List.of();
        }
    }

    /**
     * Handles player reports: files them, pings the team's leaders (with a sound),
     * confirms to the reporter, and supports listing/resolving by leaders.
     */
    static class ReportManager {

        private final JavaPlugin plugin;
        private final StorageManager storage;
        private final TeamsConfig teams;
        private final Messages messages;
        private final Sounds sounds;

        public ReportManager(JavaPlugin plugin, StorageManager storage, TeamsConfig teams,
                             Messages messages, Sounds sounds) {
            this.plugin = plugin;
            this.storage = storage;
            this.teams = teams;
            this.messages = messages;
            this.sounds = sounds;
        }

        /** Files a report and notifies the team's leaders. */
        public void file(Player reporter, String reportedName, String team, String reason, String estimatedTime) {
            Report report = new Report(UUID.randomUUID().toString(), reporter.getName(), reportedName,
                    team, reason, estimatedTime, System.currentTimeMillis());
            storage.addReport(report);

            // Ping every ONLINE leader of this team, with a sound.
            Sound sound = sounds.get("report");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (teams.leaderNamesOf(team).contains(online.getName().toLowerCase(java.util.Locale.ROOT))) {
                    online.sendMessage(messages.prefixed("report-notify", Messages.ph(
                            "reporter", reporter.getName(), "reported", reportedName,
                            "reason", reason, "time", estimatedTime)));
                    if (sound != null) online.playSound(sound);
                }
            }

            reporter.sendMessage(messages.prefixed("report-sent", Messages.ph("reported", reportedName)));
            plugin.getLogger().info("[TeamMod] REPORT by " + reporter.getName() + " against " + reportedName
                    + " (team " + team + "). Reason: " + reason + " | est. time: " + estimatedTime);
        }

        public List<Report> list(String team) {
            return storage.getReportsForTeam(team);
        }

        public int openCountForTeam(String team) {
            return storage.getReportsForTeam(team).size();
        }

        /** Notifies a leader (message + sound) if their team has open reports. */
        public void notifyOpenReports(Player leader, String team) {
            int open = openCountForTeam(team);
            if (open <= 0) return;
            leader.sendMessage(messages.prefixed("reports-login-notice", Messages.ph("count", String.valueOf(open))));
            Sound sound = sounds.get("report");
            if (sound != null) leader.playSound(sound);
        }

        /**
         * Resolves (removes) the report at a 1-based index within the team's list.
         * @return the reported player's name if one was removed, otherwise null.
         */
        public String resolve(String team, int oneBasedIndex) {
            List<Report> list = storage.getReportsForTeam(team);
            if (oneBasedIndex < 1 || oneBasedIndex > list.size()) return null;
            Report r = list.get(oneBasedIndex - 1);
            storage.removeReport(r.getId());
            return r.getReported();
        }
    }

    /**
     * Loads and provides the per-action sound effects played when TeamMod broadcasts
     * a message. Configured under the {@code sounds:} section of config.yml; each
     * action type ("kick", "ban", "mute", "auto-mute", "warn", "unban", "unmute")
     * maps to a Minecraft sound key plus volume and pitch.
     */
    static class Sounds {

        private final JavaPlugin plugin;
        private final Map<String, Sound> byType = new HashMap<>();
        private boolean enabled;

        public Sounds(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        public void load() {
            byType.clear();
            enabled = plugin.getConfig().getBoolean("sounds.enabled", true);
            if (!enabled) return;

            ConfigurationSection root = plugin.getConfig().getConfigurationSection("sounds");
            if (root == null) return;

            for (String type : root.getKeys(false)) {
                if (type.equalsIgnoreCase("enabled")) continue;
                ConfigurationSection sec = root.getConfigurationSection(type);
                if (sec == null) continue;
                String key = sec.getString("sound", "");
                if (key == null || key.isBlank()) continue;
                float volume = (float) sec.getDouble("volume", 1.0);
                float pitch = (float) sec.getDouble("pitch", 1.0);
                try {
                    byType.put(type.toLowerCase(java.util.Locale.ROOT),
                            Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch));
                } catch (Exception ex) {
                    plugin.getLogger().warning("Invalid sound key for '" + type + "': " + key);
                }
            }
        }

        /** @return the configured sound for this action type, or null if none / disabled. */
        public Sound get(String type) {
            if (!enabled || type == null) return null;
            return byType.get(type.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * All on-disk state for TeamMod, in {@code <dataFolder>/punishments.yml}:
     * active punishments, an append-only history, and per-team warning counts.
     * Teams are identified by their config name (e.g. "team1").
     */
    static class StorageManager {

        private final JavaPlugin plugin;
        private final File file;

        // active is read from the (async) chat listener, so keep it concurrent.
        private final Map<String, Punishment> active = new ConcurrentHashMap<>();
        private final List<Punishment> history = new ArrayList<>();
        private final Map<String, Map<UUID, Integer>> warnings = new ConcurrentHashMap<>();
        private final List<Report> reports = new ArrayList<>();

        public StorageManager(JavaPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "punishments.yml");
        }

        // --- loading / saving ----------------------------------------------------

        public void load() {
            active.clear();
            history.clear();
            warnings.clear();
            reports.clear();
            if (!file.exists()) return;

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

            ConfigurationSection activeSec = yaml.getConfigurationSection("active");
            if (activeSec != null) {
                for (String id : activeSec.getKeys(false)) {
                    ConfigurationSection s = activeSec.getConfigurationSection(id);
                    if (s == null) continue;
                    Punishment p = Punishment.load(id, s);
                    if (p != null) active.put(id, p);
                    else plugin.getLogger().warning("Skipping malformed active punishment: " + id);
                }
            }

            List<Map<?, ?>> historyList = yaml.getMapList("history");
            int idx = 0;
            for (Map<?, ?> raw : historyList) {
                YamlConfiguration tmp = new YamlConfiguration();
                ConfigurationSection s = tmp.createSection("e");
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    s.set(String.valueOf(e.getKey()), e.getValue());
                }
                Punishment p = Punishment.load("hist-" + (idx++), s);
                if (p != null) history.add(p);
            }

            ConfigurationSection warnSec = yaml.getConfigurationSection("warnings");
            if (warnSec != null) {
                for (String team : warnSec.getKeys(false)) {
                    ConfigurationSection teamSec = warnSec.getConfigurationSection(team);
                    if (teamSec == null) continue;
                    Map<UUID, Integer> map = new HashMap<>();
                    for (String playerKey : teamSec.getKeys(false)) {
                        try {
                            map.put(UUID.fromString(playerKey), teamSec.getInt(playerKey));
                        } catch (IllegalArgumentException ex) {
                            plugin.getLogger().warning("Skipping malformed warning uuid: " + playerKey);
                        }
                    }
                    warnings.put(team, map);
                }
            }

            for (Map<?, ?> raw : yaml.getMapList("reports")) {
                YamlConfiguration tmp = new YamlConfiguration();
                ConfigurationSection s = tmp.createSection("e");
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    s.set(String.valueOf(e.getKey()), e.getValue());
                }
                Report r = Report.fromSection(s);
                if (r != null) reports.add(r);
            }
        }

        public void save() {
            YamlConfiguration yaml = new YamlConfiguration();

            ConfigurationSection activeSec = yaml.createSection("active");
            for (Punishment p : active.values()) {
                p.save(activeSec.createSection(p.getId()));
            }

            List<Map<String, Object>> historyList = new ArrayList<>();
            for (Punishment p : history) {
                YamlConfiguration tmp = new YamlConfiguration();
                ConfigurationSection s = tmp.createSection("e");
                p.save(s);
                historyList.add(sectionToMap(s));
            }
            yaml.set("history", historyList);

            ConfigurationSection warnSec = yaml.createSection("warnings");
            for (Map.Entry<String, Map<UUID, Integer>> teamEntry : warnings.entrySet()) {
                if (teamEntry.getValue().isEmpty()) continue;
                ConfigurationSection teamSec = warnSec.createSection(teamEntry.getKey());
                for (Map.Entry<UUID, Integer> pe : teamEntry.getValue().entrySet()) {
                    if (pe.getValue() != null && pe.getValue() > 0) {
                        teamSec.set(pe.getKey().toString(), pe.getValue());
                    }
                }
            }

            List<Map<String, Object>> reportList = new ArrayList<>();
            for (Report r : reports) reportList.add(r.toMap());
            yaml.set("reports", reportList);

            try {
                if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save punishments.yml", e);
            }
        }

        private Map<String, Object> sectionToMap(ConfigurationSection s) {
            Map<String, Object> map = new HashMap<>();
            for (String key : s.getKeys(false)) map.put(key, s.get(key));
            return map;
        }

        // --- active punishments --------------------------------------------------

        public void addActive(Punishment p) {
            active.put(p.getId(), p);
            save();
        }

        public Punishment removeActive(String id) {
            Punishment removed = active.remove(id);
            if (removed != null) save();
            return removed;
        }

        public Punishment getActiveById(String id) {
            return active.get(id);
        }

        public Collection<Punishment> getActive() {
            return new ArrayList<>(active.values());
        }

        public List<Punishment> getActiveForTeam(String team) {
            List<Punishment> out = new ArrayList<>();
            for (Punishment p : active.values()) {
                if (team.equals(p.getTeam())) out.add(p);
            }
            return out;
        }

        /** First active punishment on {@code team} for {@code targetUuid} of a type, or null. */
        public Punishment findActive(String team, UUID targetUuid, PunishmentType type) {
            for (Punishment p : active.values()) {
                if (!team.equals(p.getTeam())) continue;
                if (!targetUuid.equals(p.getTargetUuid())) continue;
                if (p.getType() == type) return p;
            }
            return null;
        }

        /** First active punishment of a type for a player on ANY team (used by listeners). */
        public Punishment findActiveByPlayer(UUID targetUuid, PunishmentType type) {
            for (Punishment p : active.values()) {
                if (targetUuid.equals(p.getTargetUuid()) && p.getType() == type) return p;
            }
            return null;
        }

        // --- history -------------------------------------------------------------

        public void addHistory(Punishment p) {
            history.add(p);
            save();
        }

        public List<Punishment> getHistoryForTarget(UUID targetUuid) {
            List<Punishment> out = new ArrayList<>();
            for (Punishment p : history) {
                if (targetUuid.equals(p.getTargetUuid())) out.add(p);
            }
            return out;
        }

        // --- warnings ------------------------------------------------------------

        public int getWarnings(String team, UUID playerUuid) {
            Map<UUID, Integer> map = warnings.get(team);
            return map == null ? 0 : map.getOrDefault(playerUuid, 0);
        }

        public int incrementWarnings(String team, UUID playerUuid) {
            Map<UUID, Integer> map = warnings.computeIfAbsent(team, k -> new HashMap<>());
            int newCount = map.getOrDefault(playerUuid, 0) + 1;
            map.put(playerUuid, newCount);
            save();
            return newCount;
        }

        public void resetWarnings(String team, UUID playerUuid) {
            Map<UUID, Integer> map = warnings.get(team);
            if (map != null && map.remove(playerUuid) != null) save();
        }

        // --- reports -------------------------------------------------------------

        public void addReport(Report r) {
            reports.add(r);
            save();
        }

        public List<Report> getReportsForTeam(String team) {
            List<Report> out = new ArrayList<>();
            for (Report r : reports) {
                if (team.equals(r.getTeam())) out.add(r);
            }
            return out;
        }

        /** Removes a report by id. Returns true if one was removed. */
        public boolean removeReport(String id) {
            boolean removed = reports.removeIf(r -> r.getId().equals(id));
            if (removed) save();
            return removed;
        }
    }

    /**
     * The source of truth for who is on which team and who leads it.
     *
     * <p>Loaded from the {@code teams:} section of config.yml. All lookups are by
     * (case-insensitive) player name, because that is what server owners edit. This
     * deliberately does NOT rely on BetterTeams' live membership/rank, so access
     * control is predictable and editable by hand or via the in-game management
     * commands.</p>
     */
    static class TeamsConfig {

        private final JavaPlugin plugin;

        // team name -> lowercase leader names / member names (insertion order kept)
        private final Map<String, Set<String>> leaders = new LinkedHashMap<>();
        private final Map<String, Set<String>> members = new LinkedHashMap<>();

        public TeamsConfig(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        /** (Re)reads the teams section from the current config. */
        public void load() {
            leaders.clear();
            members.clear();
            ConfigurationSection teams = plugin.getConfig().getConfigurationSection("teams");
            if (teams == null) {
                plugin.getLogger().warning("No 'teams:' section in config.yml - nobody will be able to use TeamMod commands.");
                return;
            }
            for (String team : teams.getKeys(false)) {
                ConfigurationSection sec = teams.getConfigurationSection(team);
                if (sec == null) continue;
                leaders.put(team, lowerSet(sec.getStringList("leaders")));
                members.put(team, lowerSet(sec.getStringList("members")));
            }
        }

        private Set<String> lowerSet(List<String> list) {
            Set<String> out = new LinkedHashSet<>();
            for (String s : list) {
                if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
            }
            return out;
        }

        private String key(String name) {
            return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        }

        // --- lookups -------------------------------------------------------------

        public Set<String> teamNames() {
            return leaders.keySet();
        }

        public boolean hasTeam(String team) {
            return team != null && leaders.containsKey(team);
        }

        /** The team this player LEADS, or null if they lead none. */
        public String teamLedBy(String name) {
            String n = key(name);
            for (Map.Entry<String, Set<String>> e : leaders.entrySet()) {
                if (e.getValue().contains(n)) return e.getKey();
            }
            return null;
        }

        /** True if the player is a leader of any team (i.e. may use the commands). */
        public boolean isLeaderAnywhere(String name) {
            return teamLedBy(name) != null;
        }

        public boolean isLeaderOf(String team, String name) {
            Set<String> s = leaders.get(team);
            return s != null && s.contains(key(name));
        }

        /** The team this player is ON (member or leader), or null. */
        public String teamOf(String name) {
            for (String team : leaders.keySet()) {
                if (isOnTeam(team, name)) return team;
            }
            return null;
        }

        /** Lowercase leader names of a team (used to notify leaders). */
        public Set<String> leaderNamesOf(String team) {
            return leaders.getOrDefault(team, Set.of());
        }

        /** True if the player is on this team (member OR leader). */
        public boolean isOnTeam(String team, String name) {
            String n = key(name);
            Set<String> m = members.get(team);
            if (m != null && m.contains(n)) return true;
            return isLeaderOf(team, name);
        }

        // --- management (operator commands modify config + reload) ---------------

        /** @return true if added, false if the team is unknown or the name already present. */
        public boolean addLeader(String team, String name) { return add(team, "leaders", name); }
        public boolean removeLeader(String team, String name) { return remove(team, "leaders", name); }
        public boolean addMember(String team, String name) { return add(team, "members", name); }
        public boolean removeMember(String team, String name) { return remove(team, "members", name); }

        private boolean add(String team, String kind, String name) {
            if (!hasTeam(team)) return false;
            List<String> list = plugin.getConfig().getStringList("teams." + team + "." + kind);
            for (String existing : list) {
                if (existing.equalsIgnoreCase(name)) return false; // already present
            }
            list.add(name);
            plugin.getConfig().set("teams." + team + "." + kind, list);
            plugin.saveConfig();
            load();
            return true;
        }

        private boolean remove(String team, String kind, String name) {
            if (!hasTeam(team)) return false;
            List<String> list = plugin.getConfig().getStringList("teams." + team + "." + kind);
            boolean removed = list.removeIf(s -> s.equalsIgnoreCase(name));
            if (removed) {
                plugin.getConfig().set("teams." + team + "." + kind, list);
                plugin.saveConfig();
                load();
            }
            return removed;
        }
    }

    /**
     * Tracks per-team warning counts and escalates to an automatic mute once the
     * threshold is reached, then resets the count.
     */
    static class WarningManager {

        private final JavaPlugin plugin;
        private final StorageManager storage;
        private final Messages messages;
        private final PunishmentManager punishments;

        public WarningManager(JavaPlugin plugin, StorageManager storage, Messages messages,
                              PunishmentManager punishments) {
            this.plugin = plugin;
            this.storage = storage;
            this.messages = messages;
            this.punishments = punishments;
        }

        public int getCount(String team, java.util.UUID playerUuid) {
            return storage.getWarnings(team, playerUuid);
        }

        public void warn(Player leader, OfflinePlayer target, String targetName, String team, String reason) {
            long now = System.currentTimeMillis();
            int threshold = plugin.getConfig().getInt("warn-auto-mute-threshold", 3);

            // 1) Log the warning to history.
            Punishment record = new Punishment(java.util.UUID.randomUUID().toString(), PunishmentType.WARN,
                    target.getUniqueId(), targetName, team, leader.getUniqueId(), leader.getName(),
                    reason, now, -1L, false, Punishment.Outcome.LOGGED, now);
            storage.addHistory(record);

            // 2) Bump the counter.
            int count = storage.incrementWarnings(team, target.getUniqueId());

            // 3) Notify (no duration on warns).
            punishments.broadcastToTeam(team, messages.get("warn-broadcast",
                    Messages.ph("target", targetName, "leader", leader.getName(),
                            "count", String.valueOf(count), "threshold", String.valueOf(threshold),
                            "reason", reason)));
            punishments.playActionSound(team, "warn");
            Player online = target.getPlayer();
            if (online != null) {
                online.sendMessage(messages.prefixed("warn-target",
                        Messages.ph("leader", leader.getName(), "count", String.valueOf(count),
                                "threshold", String.valueOf(threshold), "reason", reason)));
            }
            plugin.getLogger().info("[TeamMod] " + leader.getName() + " WARNED " + targetName
                    + " (" + count + "/" + threshold + "). Reason: " + reason);

            // 4) Escalate + reset.
            if (count >= threshold) {
                punishments.applyAutoMute(target, targetName, team);
                storage.resetWarnings(team, target.getUniqueId());
                plugin.getLogger().info("[TeamMod] Warning threshold reached for " + targetName
                        + "; auto-mute applied and count reset.");
            }
        }
    }
}
