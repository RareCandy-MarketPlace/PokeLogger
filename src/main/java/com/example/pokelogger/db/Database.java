package com.example.pokelogger.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight SQLite logging layer, modeled on CoreProtect's "everything is one
 * flat events table, indexed by player and time" approach.
 *
 * One connection is kept open for the life of the server and all writes are
 * funneled through a single-threaded executor upstream (see PokeLogger#logExecutor)
 * so we never hit SQLite's "database is locked" problem from concurrent writers.
 */
public class Database {

    private static final Logger LOGGER = Logger.getLogger("PokeLogger");

    private final Connection connection;

    public Database(File dbFile) throws SQLException {
        // "properties=busy_timeout=5000" lets concurrent readers/writers retry
        // briefly instead of throwing immediately.
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
        }
        createSchema();
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
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_player ON poke_log(player_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_time ON poke_log(timestamp);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_pokemon ON poke_log(pokemon_uuid);");
        }
    }

    /** Insert a single log row. Call this off the main server thread. */
    public void insert(LogEntry entry) {
        String sql = """
            INSERT INTO poke_log
                (timestamp, action, player_uuid, player_name, other_uuid, other_name,
                 pokemon_uuid, species, nickname, level, shiny, detail, nbt_snapshot)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            if (entry.level() == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, entry.level());
            ps.setInt(11, entry.shiny() ? 1 : 0);
            ps.setString(12, entry.detail());
            ps.setString(13, entry.nbtSnapshot());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to write PokeLogger entry", e);
        }
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    /** Look up the most recent actions for a given player (as either the actor or the counterparty). */
    public List<LogRow> lookupPlayer(UUID playerUuid, int limit) {
        String sql = """
            SELECT timestamp, action, player_name, other_name, species, nickname, level, shiny, detail
            FROM poke_log
            WHERE player_uuid = ? OR other_uuid = ?
            ORDER BY timestamp DESC
            LIMIT ?
        """;
        List<LogRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerUuid.toString());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LogRow(
                        rs.getLong("timestamp"),
                        rs.getString("action"),
                        rs.getString("player_name"),
                        rs.getString("other_name"),
                        rs.getString("species"),
                        rs.getString("nickname"),
                        rs.getInt("level"),
                        rs.getInt("shiny") == 1,
                        rs.getString("detail")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query PokeLogger entries", e);
        }
        return rows;
    }

    /** Look up the full history of one specific individual Pokemon by its persistent UUID. */
    public List<LogRow> lookupPokemon(UUID pokemonUuid) {
        String sql = """
            SELECT timestamp, action, player_name, other_name, species, nickname, level, shiny, detail
            FROM poke_log
            WHERE pokemon_uuid = ?
            ORDER BY timestamp ASC
        """;
        List<LogRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pokemonUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LogRow(
                        rs.getLong("timestamp"),
                        rs.getString("action"),
                        rs.getString("player_name"),
                        rs.getString("other_name"),
                        rs.getString("species"),
                        rs.getString("nickname"),
                        rs.getInt("level"),
                        rs.getInt("shiny") == 1,
                        rs.getString("detail")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query PokeLogger entries", e);
        }
        return rows;
    }

    /**
     * Fetch the most recent NBT snapshot stored for a deletion, so a rollback
     * command can reconstruct the Pokemon. Returns null if none exists (e.g.
     * we never captured a snapshot for that entry, or nothing was found).
     */
    public String lastNbtSnapshotForPokemon(UUID pokemonUuid) {
        String sql = """
            SELECT nbt_snapshot FROM poke_log
            WHERE pokemon_uuid = ? AND nbt_snapshot IS NOT NULL
            ORDER BY timestamp DESC LIMIT 1
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pokemonUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to fetch NBT snapshot", e);
        }
        return null;
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to close PokeLogger database", e);
        }
    }

    /** Simple projection used for chat/command output. */
    public record LogRow(long timestamp, String action, String playerName, String otherName,
                          String species, String nickname, int level, boolean shiny, String detail) {}
}
