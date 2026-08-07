package com.example.pokelogger.db;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Database {
    private static final Logger LOGGER = Logger.getLogger("PokeLogger");

    public Database(File dbFile) throws SQLException {
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
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_time ON poke_log(timestamp);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_poke_log_pokemon ON poke_log(pokemon_uuid);");
        }
    }

    public void insert(LogEntry entry) {
        String sql = """
            INSERT INTO poke_log
                (timestamp, action, player_uuid, player_name, other_uuid, other_name,
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

        List<LogRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LogRow(
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query PokeLogger entries", e);
        }
        return rows;
    }

        String sql = """
            FROM poke_log
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
            }
        } catch (SQLException e) {
            return null;
        }

    public void close() {
        try {
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to close PokeLogger database", e);
        }
    }

}