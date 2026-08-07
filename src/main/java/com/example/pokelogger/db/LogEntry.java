package com.example.pokelogger.db;

import java.util.UUID;

/**
 * One row's worth of data, built by the event listener and handed to the
 * Database for async insertion.
 */
public record LogEntry(
    long timestamp,
    String action,          // e.g. "TRADE", "GIFT", "DELETE", "CAPTURE", "EVOLVE", "HELD_ITEM"
    UUID playerUuid,
    String playerName,
    UUID otherUuid,         // trade/gift counterparty, if any
    String otherName,
    UUID pokemonUuid,
    String species,
    String nickname,
    Integer level,
    boolean shiny,
    String detail,          // free-text extra context, e.g. "gave -> received Charizard"
    String nbtSnapshot      // only populated for DELETE, to support rollback
) {
    public static Builder builder(String action) {
        return new Builder(action);
    }

    public static final class Builder {
        private final String action;
        private UUID playerUuid;
        private String playerName;
        private UUID otherUuid;
        private String otherName;
        private UUID pokemonUuid;
        private String species;
        private String nickname;
        private Integer level;
        private boolean shiny;
        private String detail;
        private String nbtSnapshot;

        private Builder(String action) {
            this.action = action;
        }

        public Builder player(UUID uuid, String name) { this.playerUuid = uuid; this.playerName = name; return this; }
        public Builder other(UUID uuid, String name) { this.otherUuid = uuid; this.otherName = name; return this; }
        public Builder pokemon(UUID uuid, String species, String nickname, int level, boolean shiny) {
            this.pokemonUuid = uuid; this.species = species; this.nickname = nickname;
            this.level = level; this.shiny = shiny; return this;
        }
        public Builder detail(String detail) { this.detail = detail; return this; }
        public Builder nbtSnapshot(String nbt) { this.nbtSnapshot = nbt; return this; }

        public LogEntry build() {
            return new LogEntry(System.currentTimeMillis(), action, playerUuid, playerName,
                otherUuid, otherName, pokemonUuid, species, nickname, level, shiny, detail, nbtSnapshot);
        }
    }
}
