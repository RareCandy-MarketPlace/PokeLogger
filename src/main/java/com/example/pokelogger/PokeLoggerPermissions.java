package com.example.pokelogger;

import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Registers our permission nodes with NeoForge's PermissionAPI. This is what
 * lets LuckPerms (or any other NeoForge-integrated permission plugin) grant
 * fine-grained access, e.g.:
 *   /lp user Diablo permission set pokelogger.lookup true
 *   /lp group moderator permission set pokelogger.rollback true
 *
 * Without a permission plugin installed, these nodes default to false for
 * everyone - but PokeLogCommand OR's this check with `hasPermission(2)`
 * (op), so ops always retain access either way.
 *
 * NOTE: verify these class/method names against your NeoForge version if
 * this doesn't compile - the permission node API has shifted slightly across
 * NeoForge releases.
 */
public final class PokeLoggerPermissions {
    public static PermissionNode<Boolean> LOOKUP;
    public static PermissionNode<Boolean> ROLLBACK;
    public static PermissionNode<Boolean> RELOAD;

    private PokeLoggerPermissions() {}

    public static void register(PermissionGatherEvent.Nodes event) {
        LOOKUP = new PermissionNode<>(PokeLogger.MODID, "lookup", PermissionTypes.BOOLEAN,
                (player, uuid, context) -> false);
        ROLLBACK = new PermissionNode<>(PokeLogger.MODID, "rollback", PermissionTypes.BOOLEAN,
                (player, uuid, context) -> false);
        RELOAD = new PermissionNode<>(PokeLogger.MODID, "reload", PermissionTypes.BOOLEAN,
                (player, uuid, context) -> false);
        event.addNodes(LOOKUP, ROLLBACK, RELOAD);
    }
}