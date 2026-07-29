package com.example.pokelogger.db;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Database {
    private static final Logger LOGGER = Logger.getLogger("PokeLogger");
    private File dbFile;
    private Connection connection;

    public Database(File dbFile) throws SQLException {
        this.dbFile = dbFile;
        open();
    }

    private void open() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
        }
        createSchema();
        migrateSchema();
    }

    /** Closes and reopens the connection against the same file. Used by /pokelog reload. */
    public synchronized void reload() throws SQLException {
        close();
        open();
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS poke_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    player_uuid TEXT,
                    player_name TEXT,
                    other_uuid TEXT,
                    other_name TEXT,
                    pokemon_uuid TEXT,
                    species TEXT,
                    nickname TEXT,
                    level INTEGER,
                    shiny INTEGER,
                    detail TEXT,
                    nbt_snapshot TEXT
                );
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_player ON poke_log(player_name);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_time ON poke_log(timestamp);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_pokemon ON poke_log(pokemon_uuid);");
        }
    }

    /** Adds columns introduced after the original release, for people upgrading an existing DB file. */
    private void migrateSchema() throws SQLException {
        addColumnIfMissing("change_type", "TEXT");
        addColumnIfMissing("rolled_back", "INTEGER DEFAULT 0");
    }

    private void addColumnIfMissing(String column, String definition) throws SQLException {
        boolean exists = false;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(poke_log);")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE poke_log ADD COLUMN " + column + " " + definition + ";");
            }
            LOGGER.info("PokeLogger: migrated database, added column '" + column + "'");
        }
    }

    public void insert(LogEntry entry) {
        String sql = """
            INSERT INTO poke_log
                (timestamp, action, player_uuid, player_name, other_uuid, other_name,
                 pokemon_uuid, species, nickname, level, shiny, detail, nbt_snapshot, change_type)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, entry.timestamp());
            ps.setString(2, entry.action());
            ps.setString(3, str(entry.playerUuid()));
            ps.setString(4, entry.playerName());
            ps.setString(5, str(entry.otherUuid()));
            ps.setString(6, entry.otherName());
            ps.setString(7, str(entry.pokemonUuid()));
            ps.setString(8, entry.species());
            ps.setString(9, entry.nickname());
            if (entry.level() == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, entry.level());
            ps.setInt(11, entry.shiny() ? 1 : 0);
            ps.setString(12, entry.detail());
            ps.setString(13, entry.nbtSnapshot());
            ps.setString(14, entry.changeType());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to write PokeLogger entry", e);
        }
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    /**
     * Main lookup used by /pokelog lookup and /pokelog l.
     * All filters are optional/nullable and combine with AND.
     *
     * @param actions     raw action strings (TRADE, GIFT, CAPTURE, EVOLVE, DELETE, HELD_ITEM) or null for all
     * @param changeTypes GAIN_POKEMON / LOSS_POKEMON / ITEM_GAIN / ITEM_LOSS or null for all
     * @param textSearch  matched against species, nickname, and detail (for pokemon/item name filtering) or null
     * @param sinceMillis only rows newer than this epoch millis, or null for no lower bound
     */
    public List<LogRow> lookupByName(String playerName, Set<String> actions, Set<String> changeTypes,
                                     String textSearch, Long sinceMillis, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, timestamp, action, player_name, other_name, species, nickname, level, shiny, detail, change_type, rolled_back " +
                        "FROM poke_log WHERE player_name = ? COLLATE NOCASE");

        List<Object> params = new ArrayList<>();
        params.add(playerName);

        if (actions != null && !actions.isEmpty()) {
            sql.append(" AND action IN (").append(placeholders(actions.size())).append(")");
            params.addAll(actions);
        }
        if (changeTypes != null && !changeTypes.isEmpty()) {
            sql.append(" AND change_type IN (").append(placeholders(changeTypes.size())).append(")");
            params.addAll(changeTypes);
        }
        if (textSearch != null && !textSearch.isBlank()) {
            sql.append(" AND (species LIKE ? COLLATE NOCASE OR nickname LIKE ? COLLATE NOCASE OR detail LIKE ? COLLATE NOCASE)");
            String like = "%" + textSearch + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (sinceMillis != null) {
            sql.append(" AND timestamp >= ?");
            params.add(sinceMillis);
        }
        sql.append(" ORDER BY timestamp DESC LIMIT ?");
        params.add(limit);

        List<LogRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LogRow(
                            rs.getLong("id"), rs.getLong("timestamp"), rs.getString("action"),
                            rs.getString("player_name"), rs.getString("other_name"), rs.getString("species"),
                            rs.getString("nickname"), rs.getInt("level"), rs.getInt("shiny") == 1,
                            rs.getString("detail"), rs.getString("change_type"), rs.getInt("rolled_back") == 1));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query PokeLogger entries", e);
        }
        return rows;
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    /** Nth most recent DELETE entry (1 = most recent) for a player that still has an NBT snapshot and hasn't been rolled back. */
    public RollbackCandidate findDeleteForRollback(String playerName, int index) {
        String sql = """
            SELECT id, timestamp, species, nickname, level, shiny, pokemon_uuid, nbt_snapshot
            FROM poke_log
            WHERE player_name = ? COLLATE NOCASE AND action = 'DELETE'
              AND nbt_snapshot IS NOT NULL AND rolled_back = 0
            ORDER BY timestamp DESC
            LIMIT 1 OFFSET ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setInt(2, Math.max(0, index - 1));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new RollbackCandidate(
                        rs.getLong("id"), rs.getLong("timestamp"), rs.getString("species"),
                        rs.getString("nickname"), rs.getInt("level"), rs.getInt("shiny") == 1,
                        rs.getString("pokemon_uuid"), rs.getString("nbt_snapshot"));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to find rollback candidate", e);
            return null;
        }
    }

    public void markRolledBack(long rowId) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE poke_log SET rolled_back = 1 WHERE id = ?")) {
            ps.setLong(1, rowId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to mark row rolled back", e);
        }
    }

    public void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to close PokeLogger database", e);
        }
    }

    public record LogRow(long id, long timestamp, String action, String playerName, String otherName,
                         String species, String nickname, int level, boolean shiny, String detail,
                         String changeType, boolean rolledBack) {}

    public record RollbackCandidate(long id, long timestamp, String species, String nickname,
                                    int level, boolean shiny, String pokemonUuid, String nbtSnapshot) {}
}