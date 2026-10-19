package cn.cubegarden.cosmos.jbp.client.lod;

import cn.cubegarden.cosmos.jbp.client.lod.importing.WorldImporter;
import cn.cubegarden.cosmos.jbp.client.lod.runtime.LodRuntime;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * Client commands for the background world importer, the upstream {@code /voxy import current} equivalent.
 *
 * <p>Only the region directory and registry access are resolved here on the game thread. Directory
 * listing, Anvil I/O, NBT decoding, voxelization and hierarchy writes all run on the importer's daemon
 * threads, so a large world does not stall a tick.
 */
@Environment(EnvType.CLIENT)
public final class LodCommands {
    private LodCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("jbplod")
                        .then(ClientCommands.literal("import")
                                .then(ClientCommands.literal("current").executes(LodCommands::importCurrent))
                                .then(ClientCommands.literal("status").executes(LodCommands::status))
                                .then(ClientCommands.literal("cancel").executes(LodCommands::cancel)))));
    }

    private static int importCurrent(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = source.getClient();
        var server = client.getSingleplayerServer();
        if (server == null || client.level == null) {
            source.sendError(Component.literal("[JBP-LOD] World import requires a single-player world."));
            return 0;
        }
        LodRuntime runtime = JbpLodClient.activeRuntime().orElse(null);
        if (runtime == null) {
            source.sendError(Component.literal(
                    "[JBP-LOD] No active LoD session; the dev single-player scope must be enabled."));
            return 0;
        }
        Path region = DimensionType.getStorageFolder(client.level.dimension(),
                server.getWorldPath(LevelResource.ROOT)).resolve("region");
        try {
            WorldImporter importer = runtime.session().startWorldImport(region, client.level.registryAccess());
            if (importer.state() == WorldImporter.State.RUNNING) {
                source.sendFeedback(Component.literal("[JBP-LOD] World import running: " + importer.progress()));
            } else {
                source.sendFeedback(Component.literal("[JBP-LOD] World import started for " + region));
            }
            return 1;
        } catch (RuntimeException failure) {
            source.sendError(Component.literal("[JBP-LOD] Could not start world import: " + failure));
            return 0;
        }
    }

    private static int status(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        LodRuntime runtime = JbpLodClient.activeRuntime().orElse(null);
        if (runtime == null) {
            source.sendError(Component.literal("[JBP-LOD] No active LoD session."));
            return 0;
        }
        WorldImporter importer = runtime.session().worldImporter();
        if (importer == null) {
            source.sendFeedback(Component.literal("[JBP-LOD] No world import has been started."));
            return 1;
        }
        String failure = importer.failure();
        source.sendFeedback(Component.literal("[JBP-LOD] World import " + importer.state()
                + (failure == null ? "" : " (" + failure + ")") + ": " + importer.progress()));
        return 1;
    }

    private static int cancel(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        LodRuntime runtime = JbpLodClient.activeRuntime().orElse(null);
        WorldImporter importer = runtime == null ? null : runtime.session().worldImporter();
        if (importer == null) {
            source.sendError(Component.literal("[JBP-LOD] No world import has been started."));
            return 0;
        }
        importer.cancel();
        source.sendFeedback(Component.literal("[JBP-LOD] World import cancellation requested."));
        return 1;
    }
}
