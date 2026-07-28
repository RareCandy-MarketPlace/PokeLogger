package com.example.pokelogger.listener;

import com.example.pokelogger.db.Database;
import com.example.pokelogger.db.LogEntry;
import com.pixelmonmod.pixelmon.api.events.EvolveEvent;
import com.pixelmonmod.pixelmon.api.events.HeldItemChangedEvent;
import com.pixelmonmod.pixelmon.api.events.PixelmonDeletedEvent;
import com.pixelmonmod.pixelmon.api.events.PixelmonTradeEvent;
import com.pixelmonmod.pixelmon.api.events.PokegiftEvent;
import com.pixelmonmod.pixelmon.api.events.CaptureEvent;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every handler here does the SAME thing: pull the relevant fields off the
 * Pixelmon event, build a LogEntry, and hand it to a background executor so
 * the SQLite write never blocks the server thread the event fired on.
 *
 * Register this class on the NeoForge event bus (see PokeLogger#PokeLogger).
 */
public class PokemonEventListener {

    private final Database db;
    // Single background thread keeps writes ordered and avoids concurrent
    // SQLite access entirely, sidestepping "database is locked" errors.
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PokeLogger-DB-Writer");
        t.setDaemon(true);
        return t;
    });

    public PokemonEventListener(Database db) {
        this.db = db;
    }

    private void write(LogEntry entry) {
        logExecutor.submit(() -> db.insert(entry));
    }

    private static String speciesName(Pokemon p) {
        try {
            return p.getSpecies().getName();
        } catch (Exception e) {
            return p.getDisplayName().getString();
        }
    }

    private static String nicknameOf(Pokemon p) {
        return p.hasNickname() ? p.getNickname().getString() : null;
    }

    // ---- Trades: /trade completing between two players -------------------

    @SubscribeEvent
    public void onTrade(PixelmonTradeEvent.Post event) {
        Player p1 = event.getPlayer1();
        Player p2 = event.getPlayer2();
        Pokemon mon1 = event.getPokemon1(); // was p1's, now p2's
        Pokemon mon2 = event.getPokemon2(); // was p2's, now p1's

        write(LogEntry.builder("TRADE")
            .player(p1.getUUID(), p1.getName().getString())
            .other(p2.getUUID(), p2.getName().getString())
            .pokemon(mon1.getUUID(), speciesName(mon1), nicknameOf(mon1), mon1.getPokemonLevel(), mon1.isShiny())
            .detail(p1.getName().getString() + " gave " + speciesName(mon1) + " -> received " + speciesName(mon2))
            .build());

        write(LogEntry.builder("TRADE")
            .player(p2.getUUID(), p2.getName().getString())
            .other(p1.getUUID(), p1.getName().getString())
            .pokemon(mon2.getUUID(), speciesName(mon2), nicknameOf(mon2), mon2.getPokemonLevel(), mon2.isShiny())
            .detail(p2.getName().getString() + " gave " + speciesName(mon2) + " -> received " + speciesName(mon1))
            .build());
    }

    // ---- /pokegift ----------------------------------------------------

    @SubscribeEvent
    public void onPokegift(PokegiftEvent event) {
        if (event.isCanceled()) return;
        ServerPlayer giver = event.getGiver();
        ServerPlayer receiver = event.getReceiver();
        Pokemon mon = event.getPokemon();

        write(LogEntry.builder("GIFT")
            .player(giver.getUUID(), giver.getName().getString())
            .other(receiver.getUUID(), receiver.getName().getString())
            .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
            .detail(giver.getName().getString() + " -> " + receiver.getName().getString())
            .build());
    }

    // ---- Deletion / release (PC "release", box overflow, etc) -----------

    @SubscribeEvent
    public void onDeleted(PixelmonDeletedEvent event) {
        ServerPlayer player = event.player;
        Pokemon mon = event.pokemon;

        // Snapshot the NBT so a future /pokelog rollback command can restore it.
        String nbt;
        try {
            nbt = mon.writeToNBT(new net.minecraft.nbt.CompoundTag(), player.registryAccess()).toString();
        } catch (Exception e) {
            nbt = null;
        }

        write(LogEntry.builder("DELETE")
            .player(player.getUUID(), player.getName().getString())
            .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
            .detail("cause=" + event.deleteType)
            .nbtSnapshot(nbt)
            .build());
    }

    // ---- Successful captures --------------------------------------------

    @SubscribeEvent
    public void onCapture(CaptureEvent.SuccessfulCapture event) {
        Player player = event.getPlayer();
        Pokemon mon = event.getPokemon();
        if (player == null || mon == null) return;

        write(LogEntry.builder("CAPTURE")
            .player(player.getUUID(), player.getName().getString())
            .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
            .build());
    }

    // ---- Evolutions -------------------------------------------------------

    @SubscribeEvent
    public void onEvolve(EvolveEvent.Post event) {
        Pokemon mon = event.getPokemon();
        UUID ownerId = mon.getOwnerPlayerUUID();
        if (ownerId == null) return;

        write(LogEntry.builder("EVOLVE")
            .player(ownerId, mon.getOwnerName() != null ? mon.getOwnerName().getString() : "unknown")
            .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
            .detail("evolved to " + speciesName(mon))
            .build());
    }

    // ---- Held item changes (catches item-duping / item-swap disputes) ----

    @SubscribeEvent
    public void onHeldItemChanged(HeldItemChangedEvent.Post event) {
        Pokemon mon = event.getPokemon();
        UUID ownerId = mon.getOwnerPlayerUUID();
        if (ownerId == null) return;

        ItemStack newItem = mon.getHeldItem();
        write(LogEntry.builder("HELD_ITEM")
            .player(ownerId, mon.getOwnerName() != null ? mon.getOwnerName().getString() : "unknown")
            .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
            .detail("now holding: " + (newItem.isEmpty() ? "nothing" : newItem.getDisplayName().getString()))
            .build());
    }

    public void shutdown() {
        logExecutor.shutdown();
    }
}
