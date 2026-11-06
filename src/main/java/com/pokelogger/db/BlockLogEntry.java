package com.pokelogger.db;

import java.util.UUID;

public record BlockLogEntry(
        long timestamp,
        String action,
        UUID playerUuid,
        String playerName,
        String blockId,
        String dimension,
        int x,
        int y,
        int z) {}
