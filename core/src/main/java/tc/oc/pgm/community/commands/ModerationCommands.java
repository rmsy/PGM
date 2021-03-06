package tc.oc.pgm.community.commands;

import app.ashcon.intake.Command;
import app.ashcon.intake.CommandException;
import app.ashcon.intake.bukkit.parametric.Type;
import app.ashcon.intake.bukkit.parametric.annotation.Fallback;
import app.ashcon.intake.parametric.annotation.Default;
import app.ashcon.intake.parametric.annotation.Switch;
import app.ashcon.intake.parametric.annotation.Text;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tc.oc.pgm.Config;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.chat.Audience;
import tc.oc.pgm.api.chat.Sound;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchManager;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.player.Username;
import tc.oc.pgm.community.events.PlayerPunishmentEvent;
import tc.oc.pgm.listeners.ChatDispatcher;
import tc.oc.pgm.util.PrettyPaginatedComponentResults;
import tc.oc.pgm.util.XMLUtils;
import tc.oc.util.bukkit.component.Component;
import tc.oc.util.bukkit.component.ComponentRenderers;
import tc.oc.util.bukkit.component.ComponentUtils;
import tc.oc.util.bukkit.component.Components;
import tc.oc.util.bukkit.component.PeriodFormats;
import tc.oc.util.bukkit.component.types.PersonalizedText;
import tc.oc.util.bukkit.component.types.PersonalizedTranslatable;
import tc.oc.util.bukkit.named.NameStyle;

public class ModerationCommands {

  private static final Sound WARN_SOUND = new Sound("mob.enderdragon.growl", 1f, 1f);

  private static final Component WARN_SYMBOL =
      new PersonalizedText(" \u26a0 ").color(ChatColor.YELLOW);
  private static final Component BROADCAST_DIV =
      new PersonalizedText(" \u00BB ").color(ChatColor.GRAY);
  private static final Component CONSOLE_NAME =
      new PersonalizedTranslatable("console")
          .getPersonalizedText()
          .color(ChatColor.DARK_AQUA)
          .italic(true);

  private final ChatDispatcher chat;

  public ModerationCommands(ChatDispatcher chat) {
    this.chat = chat;
  }

  @Command(
      aliases = {"staff", "mods", "admins"},
      desc = "List the online staff members")
  public void staff(CommandSender sender, Match match) {
    // List of online staff based off of permission
    List<Component> onlineStaff =
        match.getPlayers().stream()
            .filter(player -> player.getBukkit().hasPermission(Permissions.STAFF))
            .map(player -> player.getStyledName(NameStyle.FANCY))
            .collect(Collectors.toList());

    // FORMAT: Online Staff ({count}): {names}
    Component staffCount =
        new PersonalizedText(Integer.toString(onlineStaff.size()))
            .color(onlineStaff.isEmpty() ? ChatColor.RED : ChatColor.AQUA);

    Component content =
        onlineStaff.isEmpty()
            ? new PersonalizedTranslatable("moderation.staff.empty")
                .getPersonalizedText()
                .color(ChatColor.RED)
            : new Component(
                Components.join(new PersonalizedText(", ").color(ChatColor.GRAY), onlineStaff));

    Component staff =
        new PersonalizedTranslatable("moderation.staff.name", staffCount, content)
            .getPersonalizedText()
            .color(ChatColor.GRAY);

    // Send message
    sender.sendMessage(staff);
  }

  @Command(
      aliases = {"mute", "m"},
      usage = "<player> <reason>",
      desc = "Mute a player",
      perms = Permissions.MUTE)
  public void mute(CommandSender sender, Player target, Match match, @Text String reason) {
    MatchPlayer targetMatchPlayer = match.getPlayer(target);
    if (chat.isMuted(targetMatchPlayer)) {
      sender.sendMessage(
          new PersonalizedTranslatable(
                  "moderation.mute.existing", targetMatchPlayer.getStyledName(NameStyle.FANCY))
              .getPersonalizedText()
              .color(ChatColor.RED));
      return;
    }

    // Send a warning to the player to identify mute reason
    warn(sender, target, match, reason);

    if (punish(PunishmentType.MUTE, targetMatchPlayer, sender, reason, true)) {
      chat.addMuted(targetMatchPlayer);
    }
  }

  @Command(
      aliases = {"mutes", "mutelist"},
      desc = "List of muted players",
      perms = Permissions.MUTE)
  public void listMutes(CommandSender sender, MatchManager manager) throws CommandException {
    Set<UUID> mutes = chat.getMutedUUIDs();
    List<Component> onlineMutes =
        mutes.stream()
            .filter(u -> (manager.getPlayer(u) != null))
            .map(manager::getPlayer)
            .map(p -> p.getStyledName(NameStyle.CONCISE))
            .collect(Collectors.toList());
    if (onlineMutes.isEmpty()) {
      throw new CommandException(
          ComponentRenderers.toLegacyText(
              new PersonalizedTranslatable("moderation.mute.none"), sender));
    }

    Component names =
        new Component(
            Components.join(new PersonalizedText(", ").color(ChatColor.GRAY), onlineMutes));
    Component message =
        new PersonalizedText(
            new PersonalizedTranslatable("moderation.mute.list")
                .getPersonalizedText()
                .color(ChatColor.GOLD),
            new PersonalizedText(" (").color(ChatColor.GRAY),
            new PersonalizedText(Integer.toString(onlineMutes.size())).color(ChatColor.YELLOW),
            new PersonalizedText("): ").color(ChatColor.GRAY),
            names);
    sender.sendMessage(message);
  }

  @Command(
      aliases = {"unmute", "um"},
      usage = "<player>",
      desc = "Unmute a player",
      perms = Permissions.MUTE)
  public void unMute(CommandSender sender, Player target, Match match) {
    MatchPlayer targetMatchPlayer = match.getPlayer(target);
    if (chat.isMuted(targetMatchPlayer)) {
      chat.removeMuted(targetMatchPlayer);

      targetMatchPlayer.sendMessage(
          new PersonalizedTranslatable("moderation.unmute.target")
              .getPersonalizedText()
              .color(ChatColor.GREEN));

      sender.sendMessage(
          new PersonalizedTranslatable(
                  "moderation.unmute.sender", targetMatchPlayer.getStyledName(NameStyle.FANCY))
              .color(ChatColor.GRAY));
    } else {
      sender.sendMessage(
          new PersonalizedTranslatable(
                  "moderation.unmute.none", targetMatchPlayer.getStyledName(NameStyle.FANCY))
              .getPersonalizedText()
              .color(ChatColor.RED));
    }
  }

  @Command(
      aliases = {"warn", "w"},
      usage = "<player> <reason>",
      desc = "Warn a player for bad behavior",
      perms = Permissions.WARN)
  public void warn(CommandSender sender, Player target, Match match, @Text String reason) {
    MatchPlayer targetMatchPlayer = match.getPlayer(target);
    if (punish(PunishmentType.WARN, targetMatchPlayer, sender, reason, true)) {
      sendWarning(targetMatchPlayer, reason);
    }
  }

  @Command(
      aliases = {"kick", "k"},
      usage = "<player> <reason> -s (silent)",
      desc = "Kick a player from the server",
      flags = "s",
      perms = Permissions.KICK)
  public void kick(
      CommandSender sender,
      Player target,
      Match match,
      @Text String reason,
      @Switch('s') boolean silent) {
    MatchPlayer targetMatchPlayer = match.getPlayer(target);
    if (punish(PunishmentType.KICK, targetMatchPlayer, sender, reason, silent)) {
      target.kickPlayer(
          formatPunishmentScreen(
              PunishmentType.KICK, formatPunisherName(sender, match), reason, null));
    }
  }

  @Command(
      aliases = {"ban", "permban", "pb"},
      usage = "<player> <reason> -s (silent) -t (time)",
      desc = "Ban an online player from the server",
      flags = "s",
      perms = Permissions.BAN)
  public void ban(
      CommandSender sender,
      Player target,
      Match match,
      @Text String reason,
      @Switch('s') boolean silent,
      @Switch('t') Duration banLength)
      throws CommandException {
    boolean tempBan = banLength != null && !banLength.isZero();

    MatchPlayer targetMatchPlayer = match.getPlayer(target);
    Component senderName = formatPunisherName(sender, match);
    if (punish(
        tempBan ? PunishmentType.TEMP_BAN : PunishmentType.BAN,
        targetMatchPlayer,
        sender,
        reason,
        banLength,
        silent)) {
      banPlayer(
          target.getName(), reason, senderName, tempBan ? Instant.now().plus(banLength) : null);
      target.kickPlayer(
          formatPunishmentScreen(
              tempBan ? PunishmentType.TEMP_BAN : PunishmentType.BAN,
              senderName,
              reason,
              tempBan ? banLength : null));
    }
  }

  @Command(
      aliases = {"ipban", "banip", "ipb"},
      usage = "<player|ip address> <reason> -s (silent)",
      desc = "IP Ban a player from the server",
      flags = "s",
      perms = Permissions.BAN)
  public void ipBan(
      CommandSender sender,
      MatchManager manager,
      String target,
      @Text String reason,
      @Switch('s') boolean silent)
      throws CommandException {
    Player targetPlayer = Bukkit.getPlayerExact(target);

    String address = target; // Default address to what was input

    if (targetPlayer != null) {
      // If target is a player, fetch their IP and use that
      address = targetPlayer.getAddress().getAddress().getHostAddress();
    }

    // Validate if the IP is a valid IP
    if (InetAddresses.isInetAddress(address)) {
      // Special method for IP Ban
      Bukkit.getBanList(BanList.Type.IP)
          .addBan(
              address,
              reason,
              null,
              ComponentRenderers.toLegacyText(
                  formatPunisherName(sender, manager.getMatch(sender)), sender));

      // Check all online players to find those with same IP.
      for (Player player : Bukkit.getOnlinePlayers()) {
        MatchPlayer matchPlayer = manager.getPlayer(player);
        if (player.getAddress().getAddress().getHostAddress().equals(address)) {
          // Kick players with same IP
          if (punish(PunishmentType.BAN, matchPlayer, sender, reason, silent)) {

            // Ban username to prevent rejoining
            banPlayer(
                player.getName(), reason, formatPunisherName(sender, matchPlayer.getMatch()), null);

            player.kickPlayer(
                formatPunishmentScreen(
                    PunishmentType.BAN,
                    formatPunisherName(sender, manager.getMatch(sender)),
                    reason,
                    null));
          }
        }
      }
    } else {
      sender.sendMessage(
          new PersonalizedTranslatable(
                  "moderation.commands.invalidIP",
                  new PersonalizedText(address).color(ChatColor.RED).italic(true))
              .color(ChatColor.GRAY));
    }
  }

  @Command(
      aliases = {"offlineban", "offban", "ob"},
      usage = "<player> <reason> -t (length)",
      desc = "Ban an offline player from the server",
      flags = "t",
      perms = Permissions.BAN)
  public void offlineBan(
      CommandSender sender,
      MatchManager manager,
      String target,
      @Text String reason,
      @Switch('t') Duration duration)
      throws CommandException {
    boolean tempBan = duration != null && !duration.isZero();

    banPlayer(
        target,
        reason,
        formatPunisherName(sender, manager.getMatch(sender)),
        tempBan ? Instant.now().plus(duration) : null);
    broadcastPunishment(
        tempBan ? PunishmentType.TEMP_BAN : PunishmentType.BAN,
        manager.getMatch(sender),
        sender,
        new PersonalizedText(target).color(ChatColor.DARK_AQUA),
        reason,
        true,
        duration);
  }

  @Command(
      aliases = {"alts", "listalts"},
      usage = "[target] [page]",
      desc = "List online players on the same IP",
      perms = Permissions.STAFF)
  public void alts(
      Audience audience,
      CommandSender sender,
      MatchManager manager,
      @Fallback(Type.NULL) Player targetPl,
      @Default("1") int page)
      throws CommandException {
    if (targetPl != null) {
      MatchPlayer target = manager.getPlayer(targetPl);
      List<MatchPlayer> alts = getAltAccounts(targetPl, manager);
      if (alts.isEmpty()) {
        sender.sendMessage(
            new PersonalizedTranslatable(
                    "moderation.commands.noAlts", target.getStyledName(NameStyle.FANCY))
                .getPersonalizedText()
                .color(ChatColor.RED));
      } else {
        sender.sendMessage(formatAltAccountList(target, alts));
      }
    } else {
      List<Component> altAccounts = Lists.newArrayList();
      List<MatchPlayer> accountedFor = Lists.newArrayList();

      for (Player player : Bukkit.getOnlinePlayers()) {
        MatchPlayer targetMP = manager.getPlayer(player);
        List<MatchPlayer> alts = getAltAccounts(player, manager);

        if (alts.isEmpty() || accountedFor.contains(targetMP)) {
          continue;
        } else {
          altAccounts.add(formatAltAccountList(targetMP, alts));
          accountedFor.add(targetMP);
          accountedFor.addAll(alts);
        }
      }

      int perPage = 8;
      int pages = (altAccounts.size() + perPage - 1) / perPage;

      Component pageNum =
          new PersonalizedTranslatable(
                  "command.paginatedResult.page",
                  new PersonalizedText(Integer.toString(page)).color(ChatColor.DARK_AQUA),
                  new PersonalizedText(Integer.toString(pages)).color(ChatColor.DARK_AQUA))
              .getPersonalizedText()
              .color(ChatColor.GRAY);

      Component headerText =
          new PersonalizedTranslatable("moderation.alts.header")
              .getPersonalizedText()
              .color(ChatColor.DARK_AQUA);

      Component header =
          new PersonalizedText(
              headerText,
              new PersonalizedText(" (").color(ChatColor.GRAY),
              new PersonalizedText(Integer.toString(altAccounts.size())).color(ChatColor.DARK_AQUA),
              new PersonalizedText(") » Page ").color(ChatColor.GRAY),
              pageNum);

      Component formattedHeader =
          new PersonalizedText(
              ComponentUtils.horizontalLineHeading(
                  ComponentRenderers.toLegacyText(header, sender), ChatColor.BLUE));

      new PrettyPaginatedComponentResults<Component>(formattedHeader, perPage) {
        @Override
        public Component format(Component data, int index) {
          return data;
        }

        @Override
        public Component formatEmpty() throws CommandException {
          throw new CommandException("No alternate accounts found!");
        }
      }.display(audience, altAccounts, page);
    }
  }

  @Command(
      aliases = {"baninfo", "lookup", "l"},
      usage = "[player/uuid]",
      desc = "Lookup baninfo about a player",
      perms = Permissions.STAFF)
  public void banInfo(CommandSender sender, String target) throws CommandException {

    if (!XMLUtils.USERNAME_REGEX.matcher(target).matches()) {
      UUID uuid = UUID.fromString(target);
      Username username = PGM.get().getDatastore().getUsername(uuid);
      if (username.getName() != null) {
        target = username.getName();
      } else {
        throw new CommandException(
            ComponentRenderers.toLegacyText(
                new PersonalizedTranslatable(
                        "commands.invalid.target",
                        new PersonalizedText(target).color(ChatColor.AQUA))
                    .getPersonalizedText()
                    .color(ChatColor.RED),
                sender));
      }
    }

    BanEntry ban = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(target);

    if (ban == null
        || ban.getExpiration() != null && ban.getExpiration().toInstant().isBefore(Instant.now())) {
      throw new CommandException(
          ComponentRenderers.toLegacyText(
              new PersonalizedTranslatable(
                  "moderation.records.lookupNone",
                  new PersonalizedText(target).color(ChatColor.DARK_AQUA)),
              sender));
    }

    Component header =
        new PersonalizedText(
            new PersonalizedTranslatable("moderation.records.header")
                .getPersonalizedText()
                .color(ChatColor.GRAY),
            new PersonalizedText(" » ").color(ChatColor.GOLD),
            new PersonalizedText(target).color(ChatColor.DARK_AQUA).italic(true));
    new PersonalizedTranslatable(
            "moderation.records.header", new PersonalizedText(target).color(ChatColor.DARK_AQUA))
        .getPersonalizedText()
        .color(ChatColor.DARK_GREEN);
    boolean expires = ban.getExpiration() != null;
    Component banType =
        new PersonalizedTranslatable("moderation.type.ban")
            .getPersonalizedText()
            .color(ChatColor.GOLD);
    Component expireDate = Components.blank();
    if (expires) {
      String length =
          ComponentRenderers.toLegacyText(
              PeriodFormats.briefNaturalApproximate(
                  org.joda.time.Instant.ofEpochSecond(
                      ban.getCreated().toInstant().getEpochSecond()),
                  org.joda.time.Instant.ofEpochSecond(
                      ban.getExpiration().toInstant().getEpochSecond())),
              sender);
      String remaining =
          ComponentRenderers.toLegacyText(
              PeriodFormats.briefNaturalApproximate(
                  org.joda.time.Instant.now(),
                  org.joda.time.Instant.ofEpochSecond(
                      ban.getExpiration().toInstant().getEpochSecond())),
              sender);

      banType =
          new PersonalizedTranslatable(
                  "moderation.type.temp_ban",
                  new PersonalizedText(
                      length.lastIndexOf('s') != -1
                          ? length.substring(0, length.lastIndexOf('s'))
                          : length))
              .getPersonalizedText()
              .color(ChatColor.GOLD);
      expireDate =
          new PersonalizedTranslatable(
                  "moderation.screen.expires",
                  new PersonalizedText(remaining).color(ChatColor.YELLOW))
              .getPersonalizedText()
              .color(ChatColor.GRAY);
    }

    String createdAgo =
        ComponentRenderers.toLegacyText(
            PeriodFormats.relativePastApproximate(
                org.joda.time.Instant.ofEpochSecond(ban.getCreated().toInstant().getEpochSecond())),
            sender);

    Component banTypeFormatted =
        new PersonalizedTranslatable("moderation.records.type", banType)
            .getPersonalizedText()
            .color(ChatColor.GRAY);

    Component reason =
        new PersonalizedTranslatable(
                "moderation.records.reason",
                new PersonalizedText(ban.getReason()).color(ChatColor.RED))
            .getPersonalizedText()
            .color(ChatColor.GRAY);
    Component source =
        new PersonalizedText(
            new PersonalizedTranslatable(
                    "moderation.screen.signoff",
                    new PersonalizedText(ban.getSource()).color(ChatColor.AQUA))
                .getPersonalizedText()
                .color(ChatColor.GRAY),
            Components.space(),
            new PersonalizedText(createdAgo).color(ChatColor.GRAY));

    sender.sendMessage(
        ComponentUtils.horizontalLineHeading(
            ComponentRenderers.toLegacyText(header, sender), ChatColor.DARK_PURPLE));
    sender.sendMessage(banTypeFormatted);
    sender.sendMessage(reason);
    sender.sendMessage(source);
    if (expires) {
      sender.sendMessage(expireDate);
    }
  }

  private Component formatAltAccountList(MatchPlayer target, List<MatchPlayer> alts) {
    BaseComponent names =
        Components.join(
            new PersonalizedText(", ").color(ChatColor.GRAY),
            alts.stream()
                .map(mp -> mp.getStyledName(NameStyle.CONCISE))
                .collect(Collectors.toList()));
    Component size = new PersonalizedText(Integer.toString(alts.size())).color(ChatColor.YELLOW);

    return new PersonalizedText(
        new PersonalizedText("[").color(ChatColor.GOLD),
        target.getStyledName(NameStyle.VERBOSE),
        new PersonalizedText("] ").color(ChatColor.GOLD),
        new PersonalizedText("(").color(ChatColor.GRAY),
        size,
        new PersonalizedText("): ").color(ChatColor.GRAY),
        new Component(names));
  }

  private List<MatchPlayer> getAltAccounts(Player target, MatchManager manager) {
    List<MatchPlayer> sameIPs = Lists.newArrayList();
    String address = target.getAddress().getAddress().getHostAddress();

    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.getAddress().getAddress().getHostAddress().equals(address)
          && !other.equals(target)) {
        sameIPs.add(manager.getPlayer(other));
      }
    }

    return sameIPs;
  }

  private boolean punish(
      PunishmentType type,
      MatchPlayer target,
      CommandSender issuer,
      String reason,
      boolean silent) {
    return punish(type, target, issuer, reason, Duration.ZERO, silent);
  }

  private boolean punish(
      PunishmentType type,
      MatchPlayer target,
      CommandSender issuer,
      String reason,
      Duration duration,
      boolean silent) {
    PlayerPunishmentEvent event =
        new PlayerPunishmentEvent(
            issuer, target, type, reason, duration == null ? Duration.ZERO : duration, silent);
    target.getMatch().callEvent(event);
    if (event.isCancelled()) {
      if (event.getCancelMessage() != null) {
        issuer.sendMessage(event.getCancelMessage());
      }
    }

    broadcastPunishment(event);

    return !event.isCancelled();
  }

  public enum PunishmentType {
    MUTE(false),
    WARN(false),
    KICK(true),
    BAN(true),
    TEMP_BAN(true);

    private String PREFIX_TRANSLATE_KEY = "moderation.type.";
    private String SCREEN_TRANSLATE_KEY = "moderation.screen.";

    private final boolean screen;

    PunishmentType(boolean screen) {
      this.screen = screen;
    }

    public Component getPunishmentPrefix() {
      return new PersonalizedTranslatable(PREFIX_TRANSLATE_KEY + name().toLowerCase())
          .getPersonalizedText()
          .color(ChatColor.GOLD);
    }

    public Component getPunishmentPrefix(Component time) {
      return new PersonalizedTranslatable(PREFIX_TRANSLATE_KEY + name().toLowerCase(), time)
          .getPersonalizedText()
          .color(ChatColor.GOLD);
    }

    public Component getScreenComponent(Component reason) {
      if (!screen) return Components.blank();
      return new PersonalizedTranslatable(SCREEN_TRANSLATE_KEY + name().toLowerCase(), reason)
          .getPersonalizedText()
          .color(ChatColor.GOLD);
    }
  }

  /*
   * Format Punisher Name
   */
  public static Component formatPunisherName(CommandSender sender, Match match) {
    if (sender != null && sender instanceof Player) {
      MatchPlayer matchPlayer = match.getPlayer((Player) sender);
      if (matchPlayer != null) return matchPlayer.getStyledName(NameStyle.FANCY);
    }
    return CONSOLE_NAME;
  }

  /*
   * Format Reason
   */
  public static Component formatPunishmentReason(String reason) {
    return new PersonalizedText(reason).color(ChatColor.RED);
  }

  /*
   * Formatting of Kick Screens (KICK/BAN/TEMPBAN)
   */
  public static String formatPunishmentScreen(
      PunishmentType type, Component punisher, String reason, @Nullable Duration expires) {
    List<Component> lines = Lists.newArrayList();

    Component header =
        new PersonalizedText(
            ComponentUtils.horizontalLineHeading(
                Config.Moderation.getServerName(), ChatColor.DARK_GRAY));

    Component footer =
        new PersonalizedText(
            ComponentUtils.horizontalLine(ChatColor.DARK_GRAY, ComponentUtils.MAX_CHAT_WIDTH));

    lines.add(header); // Header Line (server name) - START
    lines.add(Components.blank());
    lines.add(type.getScreenComponent(formatPunishmentReason(reason))); // The reason
    lines.add(Components.blank());

    // If punishment expires, inform user when
    if (expires != null) {
      Component timeLeft =
          PeriodFormats.briefNaturalApproximate(
              org.joda.time.Duration.standardSeconds(expires.getSeconds()));
      lines.add(
          new PersonalizedTranslatable("moderation.screen.expires", timeLeft)
              .getPersonalizedText()
              .color(ChatColor.GRAY));
      lines.add(Components.blank());
    }

    // Staff sign-off
    lines.add(
        new PersonalizedTranslatable("moderation.screen.signoff", punisher)
            .getPersonalizedText()
            .color(ChatColor.GRAY)); // The sign-off of who performed the punishment

    // Link to rules for review by player
    if (Config.Moderation.isRuleLinkVisible()) {
      Component rules = new PersonalizedText(Config.Moderation.getRulesLink());

      lines.add(Components.blank());
      lines.add(
          new PersonalizedTranslatable("moderation.screen.rulesLink", rules)
              .getPersonalizedText()
              .color(ChatColor.GRAY)); // A link to the rules
    }

    // Configurable last line (for appeal message or etc)
    if (Config.Moderation.isAppealVisible() && type.equals(PunishmentType.BAN)) {
      lines.add(Components.blank());
      lines.add(new PersonalizedText(Config.Moderation.getAppealMessage()));
    }

    lines.add(Components.blank());
    lines.add(footer); // Footer line - END

    return Components.join(new PersonalizedText("\n" + ChatColor.RESET), lines).toLegacyText();
  }

  /*
   * Sends a formatted title and plays a sound warning a user of their actions
   */
  private void sendWarning(MatchPlayer target, String reason) {
    Component titleWord =
        new PersonalizedTranslatable("moderation.warning")
            .getPersonalizedText()
            .color(ChatColor.DARK_RED);
    Component title = new PersonalizedText(WARN_SYMBOL, titleWord, WARN_SYMBOL);
    Component subtitle = formatPunishmentReason(reason).color(ChatColor.GOLD);

    target.showTitle(title, subtitle, 5, 200, 10);
    target.playSound(WARN_SOUND);
  }

  private void broadcastPunishment(PlayerPunishmentEvent event) {
    broadcastPunishment(
        event.getType(),
        event.getPlayer().getMatch(),
        event.getSender(),
        event.getPlayer().getStyledName(NameStyle.CONCISE),
        event.getReason(),
        event.isSilent(),
        event.getDuration());
  }

  /*
   * Broadcasts a punishment
   */
  private void broadcastPunishment(
      PunishmentType type,
      Match match,
      CommandSender sender,
      Component target,
      String reason,
      boolean silent,
      Duration length) {

    Component prefix = type.getPunishmentPrefix();
    if (length != null && !length.isZero()) {
      String time =
          ComponentRenderers.toLegacyText(
              PeriodFormats.briefNaturalApproximate(
                  org.joda.time.Duration.standardSeconds(length.getSeconds())),
              sender);
      prefix =
          type.getPunishmentPrefix(
              time.lastIndexOf('s') != -1
                  ? new PersonalizedText(time.substring(0, time.lastIndexOf('s')))
                      .color(ChatColor.GOLD)
                  : Components.blank());
    }

    Component reasonMsg = ModerationCommands.formatPunishmentReason(reason);
    Component formattedMsg =
        new PersonalizedText(
            ModerationCommands.formatPunisherName(sender, match),
            BROADCAST_DIV,
            prefix,
            BROADCAST_DIV,
            target,
            BROADCAST_DIV,
            reasonMsg);

    if (!silent) {
      match.sendMessage(formattedMsg);
    } else {
      // When -s flag is present, only broadcast to staff
      Component prefixedMsg =
          new PersonalizedText(
              new PersonalizedText("["),
              new PersonalizedText("A", ChatColor.GOLD),
              new PersonalizedText("] "),
              formattedMsg);
      match.getPlayers().stream()
          .filter(viewer -> viewer.getBukkit().hasPermission(Permissions.ADMINCHAT))
          .forEach(viewer -> viewer.sendMessage(prefixedMsg));
      Audience.get(Bukkit.getConsoleSender()).sendMessage(prefixedMsg);
    }
  }

  /*
   * Bukkit method of banning players
   * NOTE: Will use this if not handled by other plugins
   */
  private void banPlayer(
      String target, String reason, Component source, @Nullable Instant expires) {
    Bukkit.getBanList(BanList.Type.NAME)
        .addBan(
            target,
            reason,
            expires != null ? Date.from(expires) : null,
            ComponentRenderers.toLegacyText(source, Bukkit.getConsoleSender()));
  }
}
