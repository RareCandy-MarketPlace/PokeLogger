package com.pokelogger.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.pokelogger.db.Database;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.neoforged.neoforge.server.permission.PermissionAPI;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

public final class PokeLogCommand {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int DEFAULT_COUNT = 25;
    private static final int MAX_COUNT = 200;
    private static final int PAGE_SIZE = 8;
    private static final Pattern DURATION = Pattern.compile("(\\d+)([dhms])");

    private static final ExecutorService QUERY_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "PokeLogger-Query");
        t.setDaemon(true);
        return t;
    });

    private PokeLogCommand() {}

    public static void shutdown() {
        QUERY_EXECUTOR.shutdown();
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Supplier<Database> dbSupplier,
            BooleanSupplier reloadAction) {

        var queryArg = Commands.argument("query", StringArgumentType.greedyString())
                .suggests(PokeLogCommand::suggestQuery)
                .executes(ctx -> lookup(ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "query")));

        var lookupLiteral = Commands.literal("lookup")
                .requires(src -> hasNode(src, "lookup"))
                .executes(ctx -> usage(ctx.getSource()))
                .then(queryArg);

        var lLiteral = Commands.literal("l")
                .requires(src -> hasNode(src, "lookup"))
                .executes(ctx -> usage(ctx.getSource()))
                .then(queryArg);

        var confirmLiteral = Commands.literal("confirm")
                .executes(ctx -> doRollback(
                        ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "player"), getIndex(ctx), true));

        var indexArg = Commands.argument("index", IntegerArgumentType.integer(1, 500))
                .executes(ctx -> doRollback(
                        ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "player"), getIndex(ctx), false))
                .then(confirmLiteral);

        var rollbackPlayerArg = Commands.argument("player", StringArgumentType.word())
                .suggests(PokeLogCommand::suggestOnlinePlayers)
                .executes(ctx ->
                        doRollback(ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "player"), 1, false))
                .then(indexArg);

        var rollbackLiteral = Commands.literal("rollback")
                .requires(src -> hasNode(src, "rollback"))
                .then(rollbackPlayerArg);

        var undoPlayerArg = Commands.argument("player", StringArgumentType.word())
                .suggests(PokeLogCommand::suggestOnlinePlayers)
                .executes(ctx ->
                        doRollback(ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "player"), 1, false));

        var undoLiteral =
                Commands.literal("undo").requires(src -> hasNode(src, "undo")).then(undoPlayerArg);

        var purgeConfirmLiteral = Commands.literal("confirm")
                .executes(ctx ->
                        doPurge(ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "duration"), true));

        var purgeDurationArg = Commands.argument("duration", StringArgumentType.word())
                .executes(ctx ->
                        doPurge(ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "duration"), false))
                .then(purgeConfirmLiteral);

        var purgeLiteral =
                Commands.literal("purge").requires(src -> hasNode(src, "purge")).then(purgeDurationArg);

        var exportQueryArg = Commands.argument("query", StringArgumentType.greedyString())
                .executes(ctx -> doExport(ctx.getSource(), dbSupplier, StringArgumentType.getString(ctx, "query")));

        var exportLiteral = Commands.literal("export")
                .requires(src -> hasNode(src, "export"))
                .then(exportQueryArg);

        var reloadLiteral = Commands.literal("reload")
                .requires(src -> hasNode(src, "reload"))
                .executes(ctx -> {
                    boolean ok = reloadAction.getAsBoolean();
                    if (ok) {
                        ctx.getSource()
                                .sendSuccess(
                                        () -> Component.literal("PokeLogger database reloaded.")
                                                .withStyle(ChatFormatting.GREEN),
                                        true);
                        return 1;
                    } else {
                        ctx.getSource().sendFailure(Component.literal("PokeLogger reload failed - check server log."));
                        return 0;
                    }
                });

        var statusLiteral = Commands.literal("status")
                .requires(src -> hasNode(src, "status"))
                .executes(ctx -> showStatus(ctx.getSource(), dbSupplier));

        var helpLiteral = Commands.literal("help")
                .requires(src -> hasNode(src, "help"))
                .executes(ctx -> showHelp(ctx.getSource()));

        var plLiteral = Commands.literal("plr")
                .then(lookupLiteral)
                .then(lLiteral)
                .then(rollbackLiteral)
                .then(undoLiteral)
                .then(purgeLiteral)
                .then(exportLiteral)
                .then(reloadLiteral)
                .then(statusLiteral)
                .then(helpLiteral);

        dispatcher.register(plLiteral);
    }

    private static int getIndex(CommandContext<CommandSourceStack> ctx) {
        try {
            return IntegerArgumentType.getInteger(ctx, "index");
        } catch (IllegalArgumentException e) {
            return 1;
        }
    }

    private static boolean hasNode(CommandSourceStack src, String nodeName) {
        if (src.hasPermission(2)) return true;
        try {
            if (src.getEntity() instanceof ServerPlayer player) {
                var node =
                        switch (nodeName) {
                            case "lookup" -> com.pokelogger.PokeLoggerPermissions.LOOKUP;
                            case "rollback" -> com.pokelogger.PokeLoggerPermissions.ROLLBACK;
                            case "restore" -> com.pokelogger.PokeLoggerPermissions.RESTORE;
                            case "undo" -> com.pokelogger.PokeLoggerPermissions.UNDO;
                            case "reload" -> com.pokelogger.PokeLoggerPermissions.RELOAD;
                            case "purge" -> com.pokelogger.PokeLoggerPermissions.PURGE;
                            case "export" -> com.pokelogger.PokeLoggerPermissions.EXPORT;
                            case "status" -> com.pokelogger.PokeLoggerPermissions.STATUS;
                            case "help" -> com.pokelogger.PokeLoggerPermissions.HELP;
                            default -> null;
                        };
                if (node != null) {
                    return PermissionAPI.getPermission(player, node);
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int usage(CommandSourceStack source) {
        source.sendFailure(Component.literal("Usage: /plr l <player> t:<time> [a:action] [i:text] [count]  "
                + "e.g. /plr l u:diablo a:trade,gift t:1d 50   |   /plr help for full docs"));
        return 0;
    }

    private static CompletableFuture<Suggestions> suggestQuery(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String full = builder.getInput();
        int lastSpace = full.lastIndexOf(' ');
        String currentToken = lastSpace == -1 ? full : full.substring(lastSpace + 1);
        SuggestionsBuilder token = builder.createOffset(builder.getInput().length() - currentToken.length());

        String lower = currentToken.toLowerCase(Locale.ROOT);

        if (lower.startsWith("u:") || lower.startsWith("user:")) {
            String prefix = lower.startsWith("u:") ? "u:" : "user:";
            SuggestionsBuilder nameToken =
                    token.createOffset(token.getInput().length() - (currentToken.length() - prefix.length()));
            return SharedSuggestionProvider.suggest(onlinePlayerNames(ctx), nameToken);
        } else if (lower.startsWith("a:") || lower.startsWith("action:")) {
            String prefix = lower.startsWith("a:") ? "a:" : "action:";
            for (String v : List.of(
                    "trade",
                    "gift",
                    "capture",
                    "evolve",
                    "delete",
                    "helditem",
                    "pokemon",
                    "+pokemon",
                    "-pokemon",
                    "+helditem",
                    "-helditem")) {
                token.suggest(prefix + v);
            }
        } else if (lower.startsWith("i:") || lower.startsWith("include:")) {
        } else if (lower.startsWith("t:") || lower.startsWith("time:")) {
            String prefix = lower.startsWith("t:") ? "t:" : "time:";
            for (String v : List.of("10m", "30m", "1h", "6h", "1d", "3d", "7d", "30d")) {
                token.suggest(prefix + v);
            }
        } else if (lower.startsWith("c:") || lower.startsWith("count:")) {
            String prefix = lower.startsWith("c:") ? "c:" : "count:";
            for (String v : List.of("10", "25", "50", "100")) {
                token.suggest(prefix + v);
            }
        } else if (lower.startsWith("p:") || lower.startsWith("page:")) {
        } else if (currentToken.isEmpty() || !currentToken.contains(":")) {
            for (String v : List.of("u:", "user:", "a:", "action:", "i:", "include:", "t:", "time:", "c:", "count:")) {
                token.suggest(v);
            }
            return SharedSuggestionProvider.suggest(onlinePlayerNames(ctx), token);
        }
        return token.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(onlinePlayerNames(ctx), builder);
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

    private record ParsedQuery(
            String targetName,
            Set<String> actionFilter,
            Set<String> changeTypeFilter,
            String textSearch,
            Long sinceMillis,
            int count,
            int page) {}

    private static ParsedQuery parseQuery(String argsString) {
        String targetName = null;
        Set<String> actionFilter = null;
        Set<String> changeTypeFilter = null;
        String textSearch = null;
        Long sinceMillis = null;
        int count = DEFAULT_COUNT;
        int page = 0;

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
            else if (lower.startsWith("p:")) page = parseCount(rawToken.substring(2), page);
            else if (lower.startsWith("page:")) page = parseCount(rawToken.substring(5), page);
            else if (rawToken.matches("\\d+")) count = parseCount(rawToken, count);
            else if (targetName == null) targetName = rawToken;
        }
        return new ParsedQuery(
                targetName,
                actionFilter,
                changeTypeFilter,
                textSearch,
                sinceMillis,
                Math.max(1, Math.min(count, MAX_COUNT)),
                Math.max(0, page));
    }

    private static String stripPageToken(String argsString) {
        StringBuilder sb = new StringBuilder();
        for (String token : argsString.trim().split("\\s+")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("p:") || lower.startsWith("page:")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(token);
        }
        return sb.toString();
    }

    private record ActionFilterResult(Set<String> actions, Set<String> changeTypes) {}

    private static ActionFilterResult parseActionFilter(String value) {
        Set<String> actions = new LinkedHashSet<>();
        Set<String> changeTypes = new LinkedHashSet<>();

        for (String part : value.split("[/,]")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;

            switch (p) {
                case "pokemon" -> {
                    actions.add("CAPTURE");
                    actions.add("DELETE");
                    actions.add("GIFT");
                    actions.add("TRADE");
                }
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
                default -> {}
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
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int lookup(CommandSourceStack source, Supplier<Database> dbSupplier, String argsString) {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet - try again in a moment."));
            return 0;
        }

        ParsedQuery q = parseQuery(argsString);
        if (q.targetName() == null) return usage(source);
        if (q.sinceMillis() == null) {
            source.sendFailure(Component.literal("A time range is required - add t:<duration>, e.g. t:1d, t:6h, t:30m "
                    + "(use a large value like t:365d to search your whole history)."));
            return 0;
        }

        QUERY_EXECUTOR.execute(() -> {
            var rows = db.lookupByName(
                    q.targetName(), q.actionFilter(), q.changeTypeFilter(), q.textSearch(), q.sinceMillis(), q.count());

            source.getServer().execute(() -> sendLookupResults(source, q, argsString, rows));
        });
        return 1;
    }

    private static void sendLookupResults(
            CommandSourceStack source, ParsedQuery q, String argsString, List<Database.LogRow> rows) {
        if (rows.isEmpty()) {
            source.sendSuccess(
                    () -> Component.literal("No PokeLogger history found for " + q.targetName())
                            .withStyle(ChatFormatting.GRAY),
                    false);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(rows.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(q.page(), totalPages - 1));
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, rows.size());

        String filterNote = q.actionFilter() == null ? "" : " [" + String.join("/", q.actionFilter()) + "]";
        String header = "--- PokeLogger: " + q.targetName() + filterNote + "  (" + rows.size() + " total, page "
                + (page + 1) + "/" + totalPages + ") ---";
        source.sendSuccess(() -> Component.literal(header).withStyle(ChatFormatting.GOLD), false);

        for (int i = start; i < end; i++) {
            MutableComponent line = buildLine(rows.get(i));
            source.sendSuccess(() -> line, false);
        }

        if (totalPages > 1) {
            String baseQuery = stripPageToken(argsString);
            int fPage = page;
            MutableComponent nav = Component.empty();
            if (fPage > 0) {
                nav = nav.append(Component.literal("[\u25c0 Prev] ")
                        .withStyle(ChatFormatting.YELLOW)
                        .withStyle(style -> style.withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND, "/plr l " + baseQuery + " p:" + (fPage - 1)))));
            } else {
                nav = nav.append(Component.literal("[\u25c0 Prev] ").withStyle(ChatFormatting.DARK_GRAY));
            }
            nav = nav.append(Component.literal("Page " + (fPage + 1) + "/" + totalPages + " ")
                    .withStyle(ChatFormatting.GRAY));
            if (fPage < totalPages - 1) {
                nav = nav.append(Component.literal("[Next \u25b6]")
                        .withStyle(ChatFormatting.YELLOW)
                        .withStyle(style -> style.withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND, "/plr l " + baseQuery + " p:" + (fPage + 1)))));
            } else {
                nav = nav.append(Component.literal("[Next \u25b6]").withStyle(ChatFormatting.DARK_GRAY));
            }
            MutableComponent finalNav = nav;
            source.sendSuccess(() -> finalNav, false);
        }
    }

    private static MutableComponent buildLine(Database.LogRow row) {
        String time = TIME_FMT.format(Instant.ofEpochMilli(row.timestamp()));
        String shinyTag = row.shiny() ? "\u2605" : "";
        String action = row.action();

        MutableComponent line =
                Component.literal("[#" + row.id() + " " + time + "] ").withStyle(ChatFormatting.DARK_GRAY);
        line = line.append(Component.literal(pad(action, 9) + " ").withStyle(actionColor(action)));

        String mon = shinyTag + (row.species() != null ? row.species() : "?") + " Lv." + row.level();
        if (row.nickname() != null && !row.nickname().isEmpty()) mon += " \"" + row.nickname() + "\"";
        line = line.append(
                Component.literal(mon).withStyle(row.shiny() ? ChatFormatting.YELLOW : ChatFormatting.WHITE));

        if ((action.equals("TRADE") || action.equals("GIFT")) && row.otherName() != null) {
            line = line.append(Component.literal("  \u2194 " + row.otherName()).withStyle(ChatFormatting.AQUA));
        } else if (row.detail() != null && !row.detail().isEmpty()) {
            String detail = row.detail()
                    .replace("cause=PC", "released (PC/Party)")
                    .replace("cause=COMMAND", "removed via command");
            line = line.append(Component.literal("  (" + detail + ")").withStyle(ChatFormatting.GRAY));
        }

        if (row.rolledBack()) {
            line = line.append(
                    Component.literal("  [ROLLED BACK]").withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC));
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

    private static int doRollback(
            CommandSourceStack source, Supplier<Database> dbSupplier, String playerName, int index, boolean confirmed) {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet."));
            return 0;
        }

        QUERY_EXECUTOR.execute(() -> {
            var candidate = db.findDeleteForRollback(playerName, index);
            source.getServer().execute(() -> finishRollback(source, db, playerName, index, confirmed, candidate));
        });
        return 1;
    }

    private static void finishRollback(
            CommandSourceStack source,
            Database db,
            String playerName,
            int index,
            boolean confirmed,
            Database.RollbackCandidate candidate) {
        if (candidate == null) {
            source.sendFailure(Component.literal("No rollback-eligible DELETE entry #" + index + " found for "
                    + playerName + " (already rolled back entries and entries with no NBT snapshot are skipped)."));
            return;
        }

        String time = TIME_FMT.format(Instant.ofEpochMilli(candidate.timestamp()));
        String shinyTag = candidate.shiny() ? "\u2605" : "";
        String label = shinyTag + candidate.species() + " Lv." + candidate.level()
                + (candidate.nickname() != null ? " \"" + candidate.nickname() + "\"" : "");

        if (!confirmed) {
            MutableComponent preview = Component.literal(
                            "About to restore " + label + " (deleted " + time + ") to " + playerName + "'s storage. ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("[Click to confirm]")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                            .withStyle(style -> style.withClickEvent(new ClickEvent(
                                    ClickEvent.Action.RUN_COMMAND,
                                    "/plr rollback " + playerName + " " + index + " confirm"))));
            source.sendSuccess(() -> preview, false);
            return;
        }

        if (!hasNode(source, "restore")) {
            source.sendFailure(
                    Component.literal(
                            "You can preview rollbacks but don't have permission to confirm/execute them (missing pokelogger.restore)."));
            return;
        }

        try {
            restoreToStorage(source, playerName, candidate);
            QUERY_EXECUTOR.execute(() -> db.markRolledBack(candidate.id()));
            source.sendSuccess(
                    () -> Component.literal("Restored " + label + " to " + playerName + "'s storage.")
                            .withStyle(ChatFormatting.GREEN),
                    true);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Rollback failed: " + e.getMessage()));
        }
    }

    private static void restoreToStorage(
            CommandSourceStack source, String playerName, Database.RollbackCandidate candidate) throws Exception {
        var server = source.getServer();
        var targetPlayer = server.getPlayerList().getPlayerByName(playerName);

        if (targetPlayer == null) {
            throw new IllegalStateException(playerName + " must be online to receive the restored Pokemon.");
        }

        net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(candidate.nbtSnapshot());

        com.pixelmonmod.pixelmon.api.pokemon.Pokemon restored =
                com.pixelmonmod.pixelmon.api.pokemon.PokemonFactory.create(
                        com.pixelmonmod.pixelmon.api.registries.PixelmonSpecies.PIKACHU.getValueUnsafe());
        restored.readFromNBT(tag, server.registryAccess());

        var party = com.pixelmonmod.pixelmon.api.storage.StorageProxy.getPartyNow(targetPlayer);
        if (party == null) {
            throw new IllegalStateException("Could not load " + playerName + "'s party storage.");
        }

        boolean added = party.add(restored);
        if (!added) {
            throw new IllegalStateException(playerName + "'s party is full - free up a slot and try again.");
        }
    }

    private static int doPurge(
            CommandSourceStack source, Supplier<Database> dbSupplier, String durationToken, boolean confirmed) {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet."));
            return 0;
        }
        Long cutoff = parseSince(durationToken);
        if (cutoff == null) {
            source.sendFailure(Component.literal("Invalid duration - use something like 90d, 6h, 30m."));
            return 0;
        }

        QUERY_EXECUTOR.execute(() -> {
            int count = db.countOlderThan(cutoff);
            if (count == 0) {
                source.getServer()
                        .execute(() -> source.sendSuccess(
                                () -> Component.literal(
                                                "No entries older than " + durationToken + " - nothing to purge.")
                                        .withStyle(ChatFormatting.GRAY),
                                false));
                return;
            }

            if (!confirmed) {
                source.getServer().execute(() -> {
                    MutableComponent preview = Component.literal("This will permanently delete " + count
                                    + " entries older than " + durationToken + ". ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal("[Click to confirm]")
                                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                                    .withStyle(style -> style.withClickEvent(new ClickEvent(
                                            ClickEvent.Action.RUN_COMMAND,
                                            "/plr purge " + durationToken + " confirm"))));
                    source.sendSuccess(() -> preview, false);
                });
                return;
            }

            int deleted = db.deleteOlderThan(cutoff);
            source.getServer()
                    .execute(() -> source.sendSuccess(
                            () -> Component.literal("Purged " + deleted + " PokeLogger entries older than "
                                            + durationToken + ".")
                                    .withStyle(ChatFormatting.GREEN),
                            true));
        });
        return 1;
    }

    private static int doExport(CommandSourceStack source, Supplier<Database> dbSupplier, String argsString) {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet."));
            return 0;
        }

        ParsedQuery q = parseQuery(argsString);
        if (q.targetName() == null) {
            source.sendFailure(Component.literal("Usage: /plr export <player> [t:<duration>]"));
            return 0;
        }

        QUERY_EXECUTOR.execute(() -> {
            var rows = db.lookupAllForExport(q.targetName(), q.sinceMillis());
            if (rows.isEmpty()) {
                source.getServer()
                        .execute(() -> source.sendSuccess(
                                () -> Component.literal("No PokeLogger history found for " + q.targetName())
                                        .withStyle(ChatFormatting.GRAY),
                                false));
                return;
            }

            try {
                File exportDir = new File(db.getConfigDir(), "exports");
                exportDir.mkdirs();
                String safeName = q.targetName().replaceAll("[^a-zA-Z0-9_-]", "_");
                File outFile = new File(exportDir, safeName + "_" + System.currentTimeMillis() + ".txt");

                try (PrintWriter writer = new PrintWriter(new FileWriter(outFile))) {
                    writer.println("PokeLogger export for " + q.targetName() + " - " + rows.size() + " entries");
                    writer.println("Generated " + TIME_FMT.format(Instant.now()));
                    writer.println("=".repeat(60));
                    for (var row : rows) {
                        String time = TIME_FMT.format(Instant.ofEpochMilli(row.timestamp()));
                        String shinyTag = row.shiny() ? "*" : "";
                        String mon = shinyTag + (row.species() != null ? row.species() : "?") + " Lv." + row.level();
                        if (row.nickname() != null && !row.nickname().isEmpty()) mon += " \"" + row.nickname() + "\"";
                        String extra =
                                (row.action().equals("TRADE") || row.action().equals("GIFT")) && row.otherName() != null
                                        ? "-> " + row.otherName()
                                        : (row.detail() != null ? row.detail() : "");
                        writer.printf("[%s] %-9s %s  %s%n", time, row.action(), mon, extra);
                    }
                }

                final String path = outFile.getAbsolutePath();
                final int rowCount = rows.size();
                source.getServer()
                        .execute(() -> source.sendSuccess(
                                () -> Component.literal("Exported " + rowCount + " entries to " + path)
                                        .withStyle(ChatFormatting.GREEN),
                                true));
            } catch (IOException e) {
                source.getServer()
                        .execute(() -> source.sendFailure(Component.literal("Export failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int showStatus(CommandSourceStack source, Supplier<Database> dbSupplier) {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet."));
            return 0;
        }
        int total = db.countOlderThan(Long.MAX_VALUE);
        File dbFile = new File(db.getConfigDir(), "pokelog.db");
        double sizeMb = dbFile.exists() ? dbFile.length() / 1024.0 / 1024.0 : 0;

        source.sendSuccess(() -> Component.literal("--- PokeLogger Status ---").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(
                () -> Component.literal("Total logged entries: " + total).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(
                () -> Component.literal(String.format("Database size: %.2f MB", sizeMb))
                        .withStyle(ChatFormatting.GRAY),
                false);
        source.sendSuccess(
                () -> Component.literal("Location: " + dbFile.getAbsolutePath()).withStyle(ChatFormatting.DARK_GRAY),
                false);
        return total;
    }

    private static int showHelp(CommandSourceStack source) {
        List<Component> lines = List.of(
                Component.literal("=== PokeLogger Commands ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("/plr lookup <query>  ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("(alias: /plr l)").withStyle(ChatFormatting.GRAY)),
                Component.literal("  View a player's history. A time filter is required. Paginated, 8/page.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("  Query tokens (any order):").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("    u:<name>  / user:<name>     ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("player to look up").withStyle(ChatFormatting.GRAY)),
                Component.literal("    t:<dur>   / time:<dur>      ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("required, e.g. 1d, 6h, 30m, 1d12h")
                                .withStyle(ChatFormatting.GRAY)),
                Component.literal("    a:<f>     / action:<f>      ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("trade, gift, capture, evolve, delete, helditem,")
                                .withStyle(ChatFormatting.GRAY)),
                Component.literal("                                ")
                        .append(Component.literal("pokemon, +pokemon, -pokemon, +helditem, -helditem")
                                .withStyle(ChatFormatting.GRAY)),
                Component.literal("    i:<text>  / include:<text>  ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("match species / nickname / item name")
                                .withStyle(ChatFormatting.GRAY)),
                Component.literal("    c:<n>     / count:<n>       ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("max rows, default 25, max 200")
                                .withStyle(ChatFormatting.GRAY)),
                Component.literal("  Examples:").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("    /plr l diablo t:1d").withStyle(ChatFormatting.GREEN),
                Component.literal("    /plr l u:diablo a:trade,gift t:7d").withStyle(ChatFormatting.GREEN),
                Component.literal("    /plr l diablo a:+pokemon i:charizard t:30d")
                        .withStyle(ChatFormatting.GREEN),
                Component.literal("/plr rollback <player> [index]").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Preview + restore a released Pokemon. index defaults to 1 (most recent).")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("/plr undo <player>").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Shortcut: preview rollback of the player's single most recent release.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("/plr purge <duration>").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Preview + permanently delete log entries older than the given duration.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("/plr export <player> [t:<duration>]").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Write a player's full history to a .txt file in config/pokelogger/exports/.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("/plr reload").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Reopen the database connection without restarting the server.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("/plr status").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Show total entry count and database file size.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("/plr help").withStyle(ChatFormatting.YELLOW),
                Component.literal("  Show this message.").withStyle(ChatFormatting.GRAY));

        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }
}
