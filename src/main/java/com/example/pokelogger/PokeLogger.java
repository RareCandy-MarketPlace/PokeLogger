package com.example.pokelogger;

import com.example.pokelogger.command.PokeLogCommand;
import com.example.pokelogger.db.Database;
import com.example.pokelogger.listener.PokemonEventListener;
import com.pixelmonmod.pixelmon.Pixelmon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mod(PokeLogger.MODID)
public class PokeLogger {

    public static final String MODID = "pokelogger";
    private static final Logger LOGGER = Logger.getLogger("PokeLogger");

    private Database database;
    private PokemonEventListener listener;
    private File dbFile;

    public PokeLogger(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(PokeLoggerPermissions::register);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        File configDir = event.getServer().getServerDirectory().resolve("config/pokelogger").toFile();
        configDir.mkdirs();
        dbFile = new File(configDir, "pokelog.db");
        openDatabase();
    }

    private void openDatabase() {
        try {
            database = new Database(dbFile);
            listener = new PokemonEventListener(database);
            NeoForge.EVENT_BUS.register(listener);
            Pixelmon.EVENT_BUS.register(listener); // Pixelmon posts its own events on its own bus
            LOGGER.info("PokeLogger: database opened at " + dbFile.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "PokeLogger failed to open its database - logging will be disabled", e);
        }
    }

    /** Used by /pokelog reload. Returns true on success. */
    public boolean reload() {
        try {
            if (database != null) {
                database.reload();
                LOGGER.info("PokeLogger: database reloaded");
                return true;
            }
            openDatabase();
            return database != null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "PokeLogger failed to reload its database", e);
            return false;
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        PokeLogCommand.register(event.getDispatcher(), () -> database, this::reload);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (listener != null) listener.shutdown();
        if (database != null) database.close();
    }
}