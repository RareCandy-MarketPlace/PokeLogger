package com.example.pokelogger.listener;

import com.example.pokelogger.db.Database;
import com.example.pokelogger.db.LogEntry;
import com.pixelmonmod.pixelmon.api.events.CaptureEvent;
import com.pixelmonmod.pixelmon.api.events.EvolveEvent;
import com.pixelmonmod.pixelmon.api.events.HeldItemChangedEvent;
import com.pixelmonmod.pixelmon.api.events.PixelmonDeletedEvent;
import com.pixelmonmod.pixelmon.api.events.PixelmonTradeEvent;
import com.pixelmonmod.pixelmon.api.events.PokegiftEvent;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

public class PokemonEventListener {
    private final Database db;
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PokeLogger-DB-Writer");
        t.setDaemon(true);
        return t;
    });

    public PokemonEventListener(Database db) { this.db = db; }

    private void write(LogEntry entry) {
        logExecutor.submit(() -> db.insert(entry));
    }

    private static String speciesName(Pokemon p) {
        try { return p.getSpecies().getName(); } catch (Exception e) { return p.getDisplayName().getString(); }
    }

    private static String nicknameOf(Pokemon p) {
        return p.hasNickname() ? p.getNickname().getString() : null;
    }

    @SubscribeEvent
    public void onTrade(PixelmonTradeEvent.Post event) {
        Player p1 = event.getPlayer1();
        Player p2 = event.getPlayer2();
        Pokemon mon1 = event.getPokemon1();
        Pokemon mon2 = event.getPokemon2();

        write(LogEntry.builder("TRADE")
                .player(p1.getUUID(), p1.getName().getString())
                .other(p2.getUUID(), p2.getName().getString())
                .pokemon(mon1.getUUID(), speciesName(mon1), nicknameOf(mon1), mon1.getPokemonLevel(), mon1.isShiny())
                .detail("received " + speciesName(mon2) + " in return")
                .changeType(null) // net-neutral: gave one, got one back
                .build());

        write(LogEntry.builder("TRADE")
                .player(p2.getUUID(), p2.getName().getString())
                .other(p1.getUUID(), p1.getName().getString())
                .pokemon(mon2.getUUID(), speciesName(mon2), nicknameOf(mon2), mon2.getPokemonLevel(), mon2.isShiny())
                .detail("received " + speciesName(mon1) + " in return")
                .changeType(null)
                .build());
    }

    @SubscribeEvent
    public void onPokegift(PokegiftEvent event) {
        if (event.isCanceled()) return;
        ServerPlayer giver = event.getGiver();
        ServerPlayer receiver = event.getReceiver();
        Pokemon mon = event.getPokemon();

        // Giver's row: they lost this Pokemon.
        write(LogEntry.builder("GIFT")
                .player(giver.getUUID(), giver.getName().getString())
                .other(receiver.getUUID(), receiver.getName().getString())
                .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
                .detail("gave to " + receiver.getName().getString())
                .changeType("LOSS_POKEMON")
                .build());

        // Receiver's row: they gained this Pokemon. Mirrors trade's dual-row behavior.
        write(LogEntry.builder("GIFT")
                .player(receiver.getUUID(), receiver.getName().getString())
                .other(giver.getUUID(), giver.getName().getString())
                .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
                .detail("received from " + giver.getName().getString())
                .changeType("GAIN_POKEMON")
                .build());
    }

    @SubscribeEvent
    public void onDeleted(PixelmonDeletedEvent event) {
        ServerPlayer player = event.player;
        Pokemon mon = event.pokemon;

        String nbt;
        try {
            nbt = mon.writeToNBT(new CompoundTag(), player.registryAccess()).toString();
        } catch (Exception e) {
            nbt = null;
        }

        write(LogEntry.builder("DELETE")
                .player(player.getUUID(), player.getName().getString())
                .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
                .detail("cause=" + event.deleteType)
                .nbtSnapshot(nbt)
                .changeType("LOSS_POKEMON")
                .build());
    }

    @SubscribeEvent
    public void onCapture(CaptureEvent.SuccessfulCapture event) {
        Player player = event.getPlayer();
        Pokemon mon = event.getPokemon();
        if (player == null || mon == null) return;

        write(LogEntry.builder("CAPTURE")
                .player(player.getUUID(), player.getName().getString())
                .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
                .changeType("GAIN_POKEMON")
                .build());
    }

    @SubscribeEvent
    public void onEvolve(EvolveEvent.Post event) {
        Pokemon mon = event.getPokemon();
        UUID ownerId = mon.getOwnerPlayerUUID();
        if (ownerId == null) return;

        write(LogEntry.builder("EVOLVE")
                .player(ownerId, mon.getOwnerName() != null ? mon.getOwnerName().getString() : "unknown")
                .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
                .detail("evolved to " + speciesName(mon))
                .changeType(null) // same Pokemon, not a gain/loss
                .build());
    }

    @SubscribeEvent
    public void onHeldItemChanged(HeldItemChangedEvent.Post event) {
        Pokemon mon = event.getPokemon();
        UUID ownerId = mon.getOwnerPlayerUUID();
        if (ownerId == null) return;

        ItemStack newItem = mon.getHeldItem();
        boolean removed = newItem.isEmpty();
        String itemName = removed ? "nothing" : newItem.getDisplayName().getString();

        write(LogEntry.builder("HELD_ITEM")
                .player(ownerId, mon.getOwnerName() != null ? mon.getOwnerName().getString() : "unknown")
                .pokemon(mon.getUUID(), speciesName(mon), nicknameOf(mon), mon.getPokemonLevel(), mon.isShiny())
                .detail("now holding: " + itemName)
                .changeType(removed ? "ITEM_LOSS" : "ITEM_GAIN")
                .build());
    }

    public void shutdown() { logExecutor.shutdown(); }
}