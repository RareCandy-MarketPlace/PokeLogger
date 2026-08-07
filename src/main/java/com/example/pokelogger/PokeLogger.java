package com.example.pokelogger;

import com.example.pokelogger.command.PokeLogCommand;
import com.example.pokelogger.db.Database;
import com.example.pokelogger.listener.PokemonEventListener;
import com.pixelmonmod.pixelmon.Pixelmon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mod(PokeLogger.MODID)
public class PokeLogger {

    public static final String MODID = "pokelogger";
    private static final Logger LOGGER = Logger.getLogger("PokeLogger");

    private Database database;
    private PokemonEventListener listener;

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        File configDir = event.getServer().getServerDirectory().resolve("config/pokelogger").toFile();
        configDir.mkdirs();

        try {
            database = new Database(dbFile);
            listener = new PokemonEventListener(database);
            NeoForge.EVENT_BUS.register(listener);
            LOGGER.info("PokeLogger: database opened at " + dbFile.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "PokeLogger failed to open its database - logging will be disabled", e);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (listener != null) listener.shutdown();
        if (database != null) database.close();
    }
}