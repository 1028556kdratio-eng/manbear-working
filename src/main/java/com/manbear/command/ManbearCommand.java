package com.manbear.command;

import com.manbear.data.ManbearData;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import com.manbear.event.ServerEvents;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/** Operator tools for testing: /manbear spawn [chaser|faker|trapper|sleeper|behind|lurer|path|fog|cave] | remove | mode <mode> | info | profile | reset */
public class ManbearCommand {
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("manbear").requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn").executes(c -> spawn(c.getSource(), null))
                        .then(Commands.literal("chaser").executes(c -> spawn(c.getSource(), "chaser")))
                        .then(Commands.literal("faker").executes(c -> spawn(c.getSource(), "faker")))
                        .then(Commands.literal("trapper").executes(c -> spawn(c.getSource(), "trapper")))
                        .then(Commands.literal("sleeper").executes(c -> spawn(c.getSource(), "sleeper")))
                        .then(Commands.literal("behind").executes(c -> spawn(c.getSource(), "behind")))
                        .then(Commands.literal("lurer").executes(c -> spawn(c.getSource(), "lurer")))
                        .then(Commands.literal("path").executes(c -> spawn(c.getSource(), "path")))
                        .then(Commands.literal("fog").executes(c -> spawn(c.getSource(), "fog")))
                        .then(Commands.literal("cave").executes(c -> spawn(c.getSource(), "cave"))))
                .then(Commands.literal("remove").executes(c -> remove(c.getSource())))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("profile").executes(c -> profile(c.getSource())))
                .then(Commands.literal("reset").executes(c -> reset(c.getSource())))
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(ManbearMode.values()).map(m -> m.name().toLowerCase()), b))
                                .executes(c -> mode(c.getSource(), StringArgumentType.getString(c, "mode"))))));
    }

    private static int spawn(CommandSourceStack src, String kind) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            MinecraftServer server = src.getServer();
            if (ServerEvents.findActive(server) != null) {
                src.sendFailure(Component.literal("A Manbear already exists in this world. Use /manbear remove first."));
                return 0;
            }
            ManbearData.get(server).clearAll();
            ManbearEntity e = ServerEvents.spawnNear(server, p, kind);
            if (e == null) {
                src.sendFailure(Component.literal("Could not find a hidden spot nearby. Try again."));
                return 0;
            }
            src.sendSuccess(() -> Component.literal("The Manbear is out there." + (kind == null ? "" : " (" + kind + ")")), true);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
    }

    private static int remove(CommandSourceStack src) {
        ManbearEntity e = ServerEvents.findActive(src.getServer());
        if (e != null) e.discard();
        ManbearData data = ManbearData.get(src.getServer());
        data.clearAll();
        src.sendSuccess(() -> Component.literal("Manbear removed."), true);
        return 1;
    }

    private static int mode(CommandSourceStack src, String name) {
        ManbearEntity e = ServerEvents.findActive(src.getServer());
        if (e == null) { src.sendFailure(Component.literal("No Manbear is loaded.")); return 0; }
        for (ManbearMode m : ManbearMode.values()) {
            if (m.name().equalsIgnoreCase(name)) {
                e.ai().forceMode((ServerLevel) e.level(), m);
                src.sendSuccess(() -> Component.literal("Manbear mode -> " + m), true);
                return 1;
            }
        }
        src.sendFailure(Component.literal("Unknown mode."));
        return 0;
    }

    private static int info(CommandSourceStack src) {
        ManbearEntity e = ServerEvents.findActive(src.getServer());
        if (e == null) { src.sendFailure(Component.literal("No Manbear is loaded.")); return 0; }
        String s = e.ai().debugString() + String.format(" pos=%.0f,%.0f,%.0f", e.getX(), e.getY(), e.getZ());
        src.sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static int profile(CommandSourceStack src) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            String s = ManbearData.get(src.getServer()).profile(p.getUUID()).describe();
            src.sendSuccess(() -> Component.literal("What he knows about you: " + s), false);
            return 1;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static int reset(CommandSourceStack src) {
        ManbearData.get(src.getServer()).resetProfiles();
        src.sendSuccess(() -> Component.literal("Manbear forgot everything."), true);
        return 1;
    }
}
