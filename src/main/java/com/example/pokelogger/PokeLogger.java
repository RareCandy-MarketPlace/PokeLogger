package com.example.pokelogger;

import com.example.pokelogger.command.PokeLogCommand;
import com.example.pokelogger.db.Database;
import com.example.pokelogger.listener.PokemonEventListener;
import com.pixelmonmod.pixelmon.Pixelmon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
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

    public PokeLogger() {
        // Register this instance for lifecycle/server events (DB open/close,
        // command registration). Pixelmon's own events are registered
        // separately once the server (and its config dir) is known - see
        // onServerStarting below.
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        File configDir = event.getServer().getServerDirectory().resolve("config/pokelogger").toFile();
        configDir.mkdirs();
        File dbFile = new File(configDir, "pokelog.db");

        try {
            database = new Database(dbFile);
            listener = new PokemonEventListener(database);
            // Pixelmon maintains its OWN event bus (Pixelmon.EVENT_BUS),
            // separate from NeoForge.EVENT_BUS. Its events (CaptureEvent,
            // PixelmonDeletedEvent, etc.) are posted there, not on the
            // global NeoForge bus - so we must register on both.
            NeoForge.EVENT_BUS.register(listener);
            Pixelmon.EVENT_BUS.register(listener);
            LOGGER.info("PokeLogger: database opened at " + dbFile.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "PokeLogger failed to open its database - logging will be disabled", e);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // Register unconditionally - RegisterCommandsEvent fires before
        // ServerStartingEvent in the NeoForge lifecycle, so `database` may
        // still be null here. The command looks up the database lazily via
        // the supplier when it's actually executed instead.
        PokeLogCommand.register(event.getDispatcher(), () -> database);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (listener != null) listener.shutdown();
        if (database != null) database.close();
    }
}