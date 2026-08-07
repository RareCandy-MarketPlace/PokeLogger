package com.example.pokelogger;

import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

public final class PokeLoggerPermissions {
    public static PermissionNode<Boolean> LOOKUP;
    public static PermissionNode<Boolean> ROLLBACK;
    public static PermissionNode<Boolean> RESTORE;
    public static PermissionNode<Boolean> UNDO;
    public static PermissionNode<Boolean> PURGE;
    public static PermissionNode<Boolean> RELOAD;
    public static PermissionNode<Boolean> EXPORT;
    public static PermissionNode<Boolean> STATUS;
    public static PermissionNode<Boolean> HELP;

    private PokeLoggerPermissions() {}

    public static void register(PermissionGatherEvent.Nodes event) {
        LOOKUP   = node("lookup");
        ROLLBACK = node("rollback");
        RESTORE  = node("restore");
        UNDO     = node("undo");
        PURGE    = node("purge");
        RELOAD   = node("reload");
        EXPORT   = node("export");
        STATUS   = node("status");
        HELP     = node("help");

        event.addNodes(LOOKUP, ROLLBACK, RESTORE, UNDO, PURGE, RELOAD, EXPORT, STATUS, HELP);
    }

    private static PermissionNode<Boolean> node(String path) {
        return new PermissionNode<>(PokeLogger.MODID, path, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> false);
    }
}