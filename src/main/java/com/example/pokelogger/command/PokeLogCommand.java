package com.example.pokelogger.command;

import com.example.pokelogger.db.Database;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /pokelog lookup <query>        (alias: /pokelog l)
 * /pokelog rollback <player> [index] [confirm]
 * /pokelog reload
 *
 * Query tokens (any order, space separated):
 *   <name>                 bare word -> player name (first one wins)
 *   <number>                bare number -> result count
 *   u:<name>  / user:<name>          player name
 *   a:<x> / action:<x>               action filter, comma or slash separated. Accepts:
 *       trade, gift, capture, evolve, delete, helditem
 *       pokemon   -> capture, delete, gift, trade (anything pokemon-related)
 *       +pokemon  -> only rows where the player gained a Pokemon (capture, gift-received)
 *       -pokemon  -> only rows where the player lost a Pokemon (delete, gift-given)
 *       helditem / +helditem / -helditem  -> held item changes, gained/removed
 *   i:<text> / include:<text>        free-text match against species / nickname / item name
 *   t:<dur> / time:<dur>              only entries within this long, e.g. t:2h, t:1d12h, t:30m
 *   c:<n> / count:<n>                 max rows (default 25, max 200)
 */
public final class PokeLogCommand {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter
            .ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int DEFAULT_COUNT = 25;
    private static final int MAX_COUNT = 200;
    private static final Pattern DURATION = Pattern.compile("(\\d+)([dhms])");

    private PokeLogCommand() {}

    // ---------- registration ----------

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<Database> dbSupplier,
                                BooleanSupplier reloadAction) {

        var queryArg = Commands.argument("query", StringArgumentType.greedyString())
                .suggests(PokeLogCommand::suggestQuery)
                .executes(ctx -> lookup(ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "query")));

        var lookupLiteral = Commands.literal("lookup")
                .requires(src -> hasNode(src, "lookup"))
                .executes(ctx -> usage(ctx.getSource()))
                .then(queryArg);

        var helpLiteral = Commands.literal("help")
                .executes(ctx -> showHelp(ctx.getSource()));

        var lLiteral = Commands.literal("l")
                .requires(src -> hasNode(src, "lookup"))
                .executes(ctx -> usage(ctx.getSource()))
                .then(queryArg);

        var confirmLiteral = Commands.literal("confirm")
                .executes(ctx -> doRollback(ctx.getSource(), dbSupplier,
                        StringArgumentType.getString(ctx, "player"), getIndex(ctx), true));

        var indexArg = Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 500))
                .executes(ctx -> doRollback(ctx.getSource(), dbSupplier,
                        StringArgumentType.getString(ctx, "player"), getIndex(ctx), false))
                .then(confirmLiteral);

        var rollbackPlayerArg = Commands.argument("player", StringArgumentType.word())
                .suggests(PokeLogCommand::suggestOnlinePlayers)
                .executes(ctx -> doRollback(ctx.getSource(), dbSupplier,
                        StringArgumentType.getString(ctx, "player"), 1, false))
                .then(indexArg);

        var rollbackLiteral = Commands.literal("rollback")
                .requires(src -> hasNode(src, "rollback"))
                .then(rollbackPlayerArg);

        var reloadLiteral = Commands.literal("reload")
                .requires(src -> hasNode(src, "reload"))
                .executes(ctx -> {
                    boolean ok = reloadAction.getAsBoolean();
                    if (ok) {
                        ctx.getSource().sendSuccess(() -> Component.literal("PokeLogger database reloaded.")
                                .withStyle(ChatFormatting.GREEN), true);
                        return 1;
                    } else {
                        ctx.getSource().sendFailure(Component.literal("PokeLogger reload failed - check server log."));
                        return 0;
                    }
                });

        var pokelogLiteral = Commands.literal("pokelog")
                .then(lookupLiteral)
                .then(lLiteral)
                .then(rollbackLiteral)
                .then(reloadLiteral)
                .then(helpLiteral);

        dispatcher.register(pokelogLiteral);
    }

    private static int getIndex(CommandContext<CommandSourceStack> ctx) {
        try {
            return com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index");
        } catch (IllegalArgumentException e) {
            return 1;
        }
    }

    /** Ops always pass. Otherwise defers to a LuckPerms-visible NeoForge permission node. */
    private static boolean hasNode(CommandSourceStack src, String nodeName) {
        if (src.hasPermission(2)) return true;
        try {
            if (src.getEntity() instanceof ServerPlayer player) {
                var node = switch (nodeName) {
                    case "lookup" -> com.example.pokelogger.PokeLoggerPermissions.LOOKUP;
                    case "rollback" -> com.example.pokelogger.PokeLoggerPermissions.ROLLBACK;
                    case "reload" -> com.example.pokelogger.PokeLoggerPermissions.RELOAD;
                    default -> null;
                };
                if (node != null) {
                    return PermissionAPI.getPermission(player, node);
                }
            }
        } catch (Throwable ignored) {
            // Permission API unavailable or node not yet registered - fall through to op-only.
        }
        return false;
    }

    private static int usage(CommandSourceStack source) {
        source.sendFailure(Component.literal(
                "Usage: /pokelog l <player> [a:action] [i:text] [t:time] [count]  " +
                        "e.g. /pokelog l u:diablo a:trade,gift t:1d 50"));
        return 0;
    }

    private static int showHelp(CommandSourceStack source) {
        List<Component> lines = List.of(
                Component.literal("=== PokeLogger Commands ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),

                Component.literal("/pokelog lookup <query>  ").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("(alias: /pokelog l)").withStyle(ChatFormatting.GRAY)),
                Component.literal("  View a player's history. A time filter is required.").withStyle(ChatFormatting.GRAY),

                Component.literal("  Query tokens (any order):").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("    u:<name>  / user:<name>     ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("player to look up").withStyle(ChatFormatting.GRAY)),
                Component.literal("    t:<dur>   / time:<dur>      ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("required, e.g. 1d, 6h, 30m, 1d12h").withStyle(ChatFormatting.GRAY)),
                Component.literal("    a:<f>     / action:<f>      ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("trade, gift, capture, evolve, delete, helditem,").withStyle(ChatFormatting.GRAY)),
                Component.literal("                                ").append(
                        Component.literal("pokemon, +pokemon, -pokemon, +helditem, -helditem").withStyle(ChatFormatting.GRAY)),
                Component.literal("    i:<text>  / include:<text>  ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("match species / nickname / item name").withStyle(ChatFormatting.GRAY)),
                Component.literal("    c:<n>     / count:<n>       ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("max rows, default 25, max 200").withStyle(ChatFormatting.GRAY)),
                Component.literal("    <name> or <n> with no prefix also work (player / count)").withStyle(ChatFormatting.DARK_GRAY),

                Component.literal("  Examples:").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("    /pokelog l diablo t:1d").withStyle(ChatFormatting.GREEN),
                Component.literal("    /pokelog l u:diablo a:trade,gift t:7d").withStyle(ChatFormatting.GREEN),
                Component.literal("    /pokelog l diablo a:+pokemon i:charizard t:30d").withStyle(ChatFormatting.GREEN),

                Component.literal("/pokelog rollback <player> [index]").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Restore a released/deleted Pokemon to the player's party.").withStyle(ChatFormatting.GRAY),
                Component.literal("  index defaults to 1 (most recent). Shows a confirm prompt first.").withStyle(ChatFormatting.GRAY),

                Component.literal("/pokelog reload").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Reopen the database connection without restarting the server.").withStyle(ChatFormatting.GRAY),

                Component.literal("/pokelog help").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Show this message.").withStyle(ChatFormatting.GRAY)
        );

        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    // ---------- tab completion ----------

    private static CompletableFuture<Suggestions> suggestQuery(CommandContext<CommandSourceStack> ctx,
                                                               SuggestionsBuilder builder) {
        String full = builder.getInput();
        int lastSpace = full.lastIndexOf(' ');
        String currentToken = lastSpace == -1 ? full : full.substring(lastSpace + 1);
        SuggestionsBuilder token = builder.createOffset(builder.getInput().length() - currentToken.length());

        String lower = currentToken.toLowerCase(Locale.ROOT);

        if (lower.startsWith("u:") || lower.startsWith("user:")) {
            String prefix = lower.startsWith("u:") ? "u:" : "user:";
            String partial = currentToken.substring(prefix.length());
            for (String name : onlinePlayerNames(ctx)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT))) {
                    token.suggest(prefix + name);
                }
            }
        } else if (lower.startsWith("a:") || lower.startsWith("action:")) {
            String prefix = lower.startsWith("a:") ? "a:" : "action:";
            for (String v : List.of("trade", "gift", "capture", "evolve", "delete", "helditem",
                    "pokemon", "+pokemon", "-pokemon", "+helditem", "-helditem")) {
                token.suggest(prefix + v);
            }
        } else if (lower.startsWith("i:") || lower.startsWith("include:")) {
            // Free text - nothing useful to suggest, leave as-is.
        } else if (lower.startsWith("t:") || lower.startsWith("time:")) {
            String prefix = lower.startsWith("t:") ? "t:" : "time:";
            for (String v : List.of("10m", "30m", "1h", "6h", "1d", "3d", "7d")) {
                token.suggest(prefix + v);
            }
        } else if (lower.startsWith("c:") || lower.startsWith("count:")) {
            String prefix = lower.startsWith("c:") ? "c:" : "count:";
            for (String v : List.of("10", "25", "50", "100")) {
                token.suggest(prefix + v);
            }
        } else if (currentToken.isEmpty() || !currentToken.contains(":")) {
            // Could still be a bare player name - suggest online players, plus the prefix forms.
            for (String name : onlinePlayerNames(ctx)) {
                token.suggest(name);
            }
            for (String v : List.of("u:", "user:", "a:", "action:", "i:", "include:", "t:", "time:", "c:", "count:")) {
                token.suggest(v);
            }
        }
        return token.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> ctx,
                                                                       SuggestionsBuilder builder) {
        for (String name : onlinePlayerNames(ctx)) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static List<String> onlinePlayerNames(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                    .map(p -> p.getGameProfile().getName())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------- lookup ----------

    private static int lookup(CommandSourceStack source, Supplier<Database> dbSupplier, String argsString) {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet - try again in a moment."));
            return 0;
        }

        String targetName = null;
        Set<String> actionFilter = null;
        Set<String> changeTypeFilter = null;
        String textSearch = null;
        Long sinceMillis = null;
        int count = DEFAULT_COUNT;

        for (String rawToken : argsString.trim().split("\\s+")) {
            if (rawToken.isEmpty()) continue;
            String lower = rawToken.toLowerCase(Locale.ROOT);

            if (lower.startsWith("u:")) targetName = rawToken.substring(2);
            else if (lower.startsWith("user:")) targetName = rawToken.substring(5);
            else if (lower.startsWith("a:")) {
                var parsed = parseActionFilter(rawToken.substring(2));
                actionFilter = mergeNullable(actionFilter, parsed.actions());
                changeTypeFilter = mergeNullable(changeTypeFilter, parsed.changeTypes());
            } else if (lower.startsWith("action:")) {
                var parsed = parseActionFilter(rawToken.substring(7));
                actionFilter = mergeNullable(actionFilter, parsed.actions());
                changeTypeFilter = mergeNullable(changeTypeFilter, parsed.changeTypes());
            } else if (lower.startsWith("i:")) textSearch = rawToken.substring(2);
            else if (lower.startsWith("include:")) textSearch = rawToken.substring(8);
            else if (lower.startsWith("t:")) sinceMillis = parseSince(rawToken.substring(2));
            else if (lower.startsWith("time:")) sinceMillis = parseSince(rawToken.substring(5));
            else if (lower.startsWith("c:")) count = parseCount(rawToken.substring(2), count);
            else if (lower.startsWith("count:")) count = parseCount(rawToken.substring(6), count);
            else if (rawToken.matches("\\d+")) count = parseCount(rawToken, count);
            else if (targetName == null) targetName = rawToken;
        }

        if (targetName == null) return usage(source);
        if (sinceMillis == null) {
            source.sendFailure(Component.literal(
                    "A time range is required - add t:<duration>, e.g. t:1d, t:6h, t:30m  " +
                            "(use a large value like t:365d to search your whole history)."));
            return 0;
        }
        count = Math.max(1, Math.min(count, MAX_COUNT));
        final String finalTargetName = targetName;
        final Set<String> finalActionFilter = actionFilter;
        var rows = db.lookupByName(targetName, actionFilter, changeTypeFilter, textSearch, sinceMillis, count);

        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No PokeLogger history found for " + finalTargetName)
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        String filterNote = finalActionFilter == null ? "" : " [" + String.join("/", finalActionFilter) + "]";
        source.sendSuccess(() -> Component.literal("--- PokeLogger: " + finalTargetName + filterNote + " (last " + rows.size() + ") ---")
                .withStyle(ChatFormatting.GOLD), false);

        for (var row : rows) {
            MutableComponent line = buildLine(row);
            source.sendSuccess(() -> line, false);
        }
        return rows.size();
    }

    private record ActionFilterResult(Set<String> actions, Set<String> changeTypes) {}

    private static ActionFilterResult parseActionFilter(String value) {
        Set<String> actions = new LinkedHashSet<>();
        Set<String> changeTypes = new LinkedHashSet<>();

        for (String part : value.split("[/,]")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;

            switch (p) {
                case "pokemon" -> { actions.add("CAPTURE"); actions.add("DELETE"); actions.add("GIFT"); actions.add("TRADE"); }
                case "+pokemon" -> changeTypes.add("GAIN_POKEMON");
                case "-pokemon" -> changeTypes.add("LOSS_POKEMON");
                case "helditem", "held_item" -> actions.add("HELD_ITEM");
                case "+helditem" -> changeTypes.add("ITEM_GAIN");
                case "-helditem" -> changeTypes.add("ITEM_LOSS");
                case "trade" -> actions.add("TRADE");
                case "gift" -> actions.add("GIFT");
                case "capture", "catch" -> actions.add("CAPTURE");
                case "evolve", "evolution" -> actions.add("EVOLVE");
                case "delete", "release" -> actions.add("DELETE");
                default -> { /* unrecognized token - ignore rather than error the whole filter */ }
            }
        }
        return new ActionFilterResult(actions.isEmpty() ? null : actions, changeTypes.isEmpty() ? null : changeTypes);
    }

    private static Set<String> mergeNullable(Set<String> a, Set<String> b) {
        if (a == null) return b;
        if (b == null) return a;
        Set<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return merged;
    }

    /** Parses durations like "2h", "1d12h", "30m", "45s" into an epoch-millis lower bound. */
    private static Long parseSince(String value) {
        Matcher m = DURATION.matcher(value.toLowerCase(Locale.ROOT));
        long totalMillis = 0;
        boolean matchedAny = false;
        while (m.find()) {
            matchedAny = true;
            long amount = Long.parseLong(m.group(1));
            totalMillis += switch (m.group(2)) {
                case "d" -> amount * 24L * 60 * 60 * 1000;
                case "h" -> amount * 60L * 60 * 1000;
                case "m" -> amount * 60L * 1000;
                case "s" -> amount * 1000L;
                default -> 0;
            };
        }
        if (!matchedAny) return null;
        return System.currentTimeMillis() - totalMillis;
    }

    private static int parseCount(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; }
    }

    private static MutableComponent buildLine(Database.LogRow row) {
        String time = TIME_FMT.format(Instant.ofEpochMilli(row.timestamp()));
        String shinyTag = row.shiny() ? "\u2605" : "";
        String action = row.action();

        MutableComponent line = Component.literal("[#" + row.id() + " " + time + "] ").withStyle(ChatFormatting.DARK_GRAY);
        line = line.append(Component.literal(pad(action, 9) + " ").withStyle(actionColor(action)));

        String mon = shinyTag + (row.species() != null ? row.species() : "?") + " Lv." + row.level();
        if (row.nickname() != null && !row.nickname().isEmpty()) mon += " \"" + row.nickname() + "\"";
        line = line.append(Component.literal(mon).withStyle(row.shiny() ? ChatFormatting.YELLOW : ChatFormatting.WHITE));

        if ((action.equals("TRADE") || action.equals("GIFT")) && row.otherName() != null) {
            String verb = "GIFT".equals(action) && "GAIN_POKEMON".equals(row.changeType()) ? "from" : "to/from";
            line = line.append(Component.literal("  \u2194 " + row.otherName()).withStyle(ChatFormatting.AQUA));
        } else if (row.detail() != null && !row.detail().isEmpty()) {
            String detail = row.detail()
                    .replace("cause=PC", "released (PC/Party)")
                    .replace("cause=COMMAND", "removed via command");
            line = line.append(Component.literal("  (" + detail + ")").withStyle(ChatFormatting.GRAY));
        }

        if (row.rolledBack()) {
            line = line.append(Component.literal("  [ROLLED BACK]").withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC));
        }
        return line;
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static ChatFormatting actionColor(String action) {
        return switch (action) {
            case "TRADE" -> ChatFormatting.LIGHT_PURPLE;
            case "GIFT" -> ChatFormatting.GREEN;
            case "DELETE" -> ChatFormatting.RED;
            case "CAPTURE" -> ChatFormatting.BLUE;
            case "EVOLVE" -> ChatFormatting.GOLD;
            case "HELD_ITEM" -> ChatFormatting.DARK_AQUA;
            default -> ChatFormatting.WHITE;
        };
    }

    // ---------- rollback ----------

    private static int doRollback(CommandSourceStack source, Supplier<Database> dbSupplier,
                                  String playerName, int index, boolean confirmed) {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet."));
            return 0;
        }

        var candidate = db.findDeleteForRollback(playerName, index);
        if (candidate == null) {
            source.sendFailure(Component.literal(
                    "No rollback-eligible DELETE entry #" + index + " found for " + playerName +
                            " (already rolled back entries and entries with no NBT snapshot are skipped)."));
            return 0;
        }

        String time = TIME_FMT.format(Instant.ofEpochMilli(candidate.timestamp()));
        String shinyTag = candidate.shiny() ? "\u2605" : "";
        String label = shinyTag + candidate.species() + " Lv." + candidate.level() +
                (candidate.nickname() != null ? " \"" + candidate.nickname() + "\"" : "");

        if (!confirmed) {
            MutableComponent preview = Component.literal(
                            "About to restore " + label + " (deleted " + time + ") to " + playerName + "'s storage. ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("[Click to confirm]")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                            .withStyle(style -> style.withClickEvent(
                                    new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                            "/pokelog rollback " + playerName + " " + index + " confirm"))));
            source.sendSuccess(() -> preview, false);
            return 1;
        }

        try {
            restoreToStorage(source, playerName, candidate);
            db.markRolledBack(candidate.id());
            source.sendSuccess(() -> Component.literal("Restored " + label + " to " + playerName + "'s storage.")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Rollback failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Reconstructs the Pokemon from its saved NBT and adds it back to the target
     * player's party (falling back to their PC if the party is full).
     *
     * NOT FULLY VERIFIED against your exact Pixelmon build - the general shape
     * (StorageProxy.getParty/.getPC + Pokemon NBT loading) is correct for the
     * Pixelmon storage API, but exact method names have drifted between
     * versions. If this section fails to compile, ctrl+click into
     * `com.pixelmonmod.pixelmon.api.storage.StorageProxy` and
     * `com.pixelmonmod.pixelmon.api.pokemon.Pokemon` in IntelliJ to find the
     * matching method names in your local jar and adjust below.
     */
    /**
     * Reconstructs the Pokemon from its saved NBT and adds it back to the
     * target player's party. Requires the player to be online, since
     * StorageProxy's party lookup needs a live ServerPlayer.
     *
     * `party.add(restored)` is the one remaining unverified call - if this
     * doesn't compile, ctrl+click (or Ctrl+B) on `add` to see PlayerPartyStorage's
     * real method name (could be `add`, `put`, or similar) and paste it back
     * to me.
     */
    private static void restoreToStorage(CommandSourceStack source, String playerName,
                                         Database.RollbackCandidate candidate) throws Exception {
        var server = source.getServer();
        var targetPlayer = server.getPlayerList().getPlayerByName(playerName);

        if (targetPlayer == null) {
            throw new IllegalStateException(playerName + " must be online to receive the restored Pokemon.");
        }

        net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(candidate.nbtSnapshot());

        // Pokemon's constructors are all protected, so we can't `new` one directly.
        // PokemonFactory.create(Species) gives us a valid placeholder instance, then
        // readFromNBT() below completely overwrites it (species included) with the
        // actual saved snapshot - the placeholder species passed here is irrelevant.
        com.pixelmonmod.pixelmon.api.pokemon.Pokemon restored =
                com.pixelmonmod.pixelmon.api.pokemon.PokemonFactory.create(
                        com.pixelmonmod.pixelmon.api.registries.PixelmonSpecies.PIKACHU.getValueUnsafe());
        restored.readFromNBT(tag, server.registryAccess());

        var party = com.pixelmonmod.pixelmon.api.storage.StorageProxy.getPartyNow(targetPlayer);
        if (party == null) {
            throw new IllegalStateException("Could not load " + playerName + "'s party storage.");
        }

        boolean added = party.add(restored); // <- verify this method name if it fails to compile
        if (!added) {
            throw new IllegalStateException(playerName + "'s party is full - free up a slot and try again.");
        }
    }
}