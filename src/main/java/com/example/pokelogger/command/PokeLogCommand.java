package com.example.pokelogger.command;

import com.example.pokelogger.db.Database;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * /pokelog lookup <player> [count]
 *
 * A rollback command (/pokelog rollback <pokemonUuid>) is intentionally left
 * as a stub - restoring an NBT snapshot safely means finding the Pokemon's
 * current storage slot and overwriting it, which depends on which storage
 * API you want to support (PC box vs party) and needs its own confirmation
 * flow, similar to CoreProtect's /co rollback preview + confirm.
 */
public final class PokeLogCommand {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter
            .ofPattern("MMM d HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private PokeLogCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<Database> dbSupplier) {
        // Built bottom-up with named variables instead of one giant nested
        // expression, so it's easy to see (and count parentheses on).

        var countArg = Commands.argument("count", IntegerArgumentType.integer(1, 200))
                .executes(ctx -> lookup(ctx.getSource(), ctx, dbSupplier,
                        IntegerArgumentType.getInteger(ctx, "count")));

        var playerArg = Commands.argument("player", GameProfileArgument.gameProfile())
                .executes(ctx -> lookup(ctx.getSource(), ctx, dbSupplier, 25))
                .then(countArg);

        var lookupLiteral = Commands.literal("lookup")
                .then(playerArg);

        var pokelogLiteral = Commands.literal("pokelog")
                .requires(src -> src.hasPermission(2)) // op-only by default
                .then(lookupLiteral);

        dispatcher.register(pokelogLiteral);
    }

    private static int lookup(CommandSourceStack source,
                              com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                              Supplier<Database> dbSupplier, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Database db = dbSupplier.get();
        if (db == null) {
            source.sendFailure(Component.literal("PokeLogger database is not ready yet - try again in a moment."));
            return 0;
        }

        Collection<com.mojang.authlib.GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        com.mojang.authlib.GameProfile target = profiles.iterator().next();
        UUID targetUuid = target.getId();

        var rows = db.lookupPlayer(targetUuid, count);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No PokeLogger history found for " + target.getName()), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("--- PokeLogger: " + target.getName() + " (last " + rows.size() + ") ---"), false);
        for (var row : rows) {
            String time = TIME_FMT.format(Instant.ofEpochMilli(row.timestamp()));
            String shinyTag = row.shiny() ? "*" : "";
            String line = String.format("[%s] %s: %s %s%s (Lv.%d)%s",
                    time, row.action(), row.playerName(),
                    shinyTag, row.species() != null ? row.species() : "?",
                    row.level(),
                    row.detail() != null ? " - " + row.detail() : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return rows.size();
    }
}