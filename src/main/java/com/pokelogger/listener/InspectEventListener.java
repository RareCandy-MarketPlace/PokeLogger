package com.pokelogger.listener;

import com.pokelogger.command.PokeLogCommand;
import com.pokelogger.db.BlockLogEntry;
import com.pokelogger.db.Database;
import com.pokelogger.db.LogEntry;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity;

public class InspectEventListener {
    private final Database db;
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PokeLogger-Block-Writer");
        t.setDaemon(true);
        return t;
    });

    public InspectEventListener(Database db) {
        this.db = db;
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PokeLogCommand.isInspecting(player.getUUID())) {
                event.setCanceled(true);
                if (!player.level().isClientSide) {
                    handleEntityInspect(player, event.getTarget());
                }
            } else if (!player.level().isClientSide && event.getTarget() instanceof PixelmonEntity pixelmon) {
                net.minecraft.world.item.ItemStack item = event.getItemStack();
                if (!item.isEmpty()) {
                    String itemName =
                            BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                    if (itemName.contains("candy") || itemName.contains("mint") || itemName.contains("potion")) {
                        logItemUsage(player, pixelmon, itemName);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onEntityAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PokeLogCommand.isInspecting(player.getUUID())) {
                event.setCanceled(true);
                if (!player.level().isClientSide) {
                    handleEntityInspect(player, event.getTarget());
                }
            }
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!player.level().isClientSide) {
                BlockPos pos = event.getPos();
                String dim = player.level().dimension().location().toString();
                String blockId = BuiltInRegistries.BLOCK
                        .getKey(event.getPlacedBlock().getBlock())
                        .toString();

                logExecutor.submit(() -> db.insertBlock(new BlockLogEntry(
                        System.currentTimeMillis(),
                        "PLACE",
                        player.getUUID(),
                        player.getName().getString(),
                        blockId,
                        dim,
                        pos.getX(),
                        pos.getY(),
                        pos.getZ())));
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            if (PokeLogCommand.isInspecting(player.getUUID())) {
                event.setCanceled(true);
                if (!player.level().isClientSide) {
                    handleBlockInspect(player, event.getPos());
                }
            } else if (!player.level().isClientSide) {
                BlockPos pos = event.getPos();
                String dim = player.level().dimension().location().toString();
                String blockId = BuiltInRegistries.BLOCK
                        .getKey(event.getState().getBlock())
                        .toString();

                logExecutor.submit(() -> db.insertBlock(new BlockLogEntry(
                        System.currentTimeMillis(),
                        "BREAK",
                        player.getUUID(),
                        player.getName().getString(),
                        blockId,
                        dim,
                        pos.getX(),
                        pos.getY(),
                        pos.getZ())));
            }
        }
    }

    @SubscribeEvent
    public void onBlockInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PokeLogCommand.isInspecting(player.getUUID())) {
                event.setCanceled(true);
                if (!player.level().isClientSide) {
                    handleBlockInspect(player, event.getPos());
                }
            }
        }
    }

    @SubscribeEvent
    public void onBlockLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PokeLogCommand.isInspecting(player.getUUID())) {
                event.setCanceled(true);
                if (!player.level().isClientSide) {
                    handleBlockInspect(player, event.getPos());
                }
            }
        }
    }

    private void handleEntityInspect(ServerPlayer player, Entity target) {
        if (target instanceof PixelmonEntity pixelmon) {
            UUID monId = pixelmon.getPokemon().getUUID();
            PokeLogCommand.runInspectLookup(player, db, monId);
        } else {
            player.sendSystemMessage(Component.literal("This entity is not a Pokemon."));
        }
    }

    private void handleBlockInspect(ServerPlayer player, BlockPos pos) {
        String dim = player.level().dimension().location().toString();
        PokeLogCommand.runBlockInspectLookup(player, db, pos.getX(), pos.getY(), pos.getZ(), dim);
    }

    private void logItemUsage(ServerPlayer player, PixelmonEntity pixelmon, String itemName) {
        UUID monId = pixelmon.getPokemon().getUUID();
        String species = pixelmon.getPokemon().getSpecies().getName();
        String nick = pixelmon.getPokemon().hasNickname()
                ? pixelmon.getPokemon().getNickname().getString()
                : null;
        int level = pixelmon.getPokemon().getPokemonLevel();
        boolean shiny = pixelmon.getPokemon().isShiny();

        logExecutor.submit(() -> db.insert(LogEntry.builder("ITEM_USED")
                .player(player.getUUID(), player.getName().getString())
                .pokemon(monId, species, nick, level, shiny)
                .detail("used item: " + itemName)
                .changeType(null)
                .build()));
    }

    public void shutdown() {
        logExecutor.shutdown();
    }
}
