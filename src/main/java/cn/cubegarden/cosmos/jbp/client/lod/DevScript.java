package cn.cubegarden.cosmos.jbp.client.lod;

import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dev-only scripted acceptance harness. Enabled only by {@code -Djbp.lod.devScript=<path or inline spec>} in a
 * Fabric development environment; it is never reachable in a release jar (no property, no registration).
 *
 * <p>Every step is keyed by the client tick count after the world, the integrated server and the LOD runtime
 * are all ready. Supported actions (one step per line in a file, or {@code ;} separated inline):
 * <pre>
 *   20 cmd gamerule doDaylightCycle false
 *   30 rd 8
 *   40 tp auto 0 0            (absolute x/z, "auto" y = world surface)
 *   50 tprel 150 0 0          (relative to the position captured at step 0)
 *   60 look -90 10            (yaw, pitch; applied client-side and to the server player)
 *   70 draw off | draw on
 *   80 shot rd2_lod_on
 *   90 stats
 *  100 quit
 * </pre>
 * Log lines are prefixed {@code [JBP-LOD] devScript:}.
 */
@Environment(EnvType.CLIENT)
public final class DevScript {
    static final String PROPERTY = "jbp.lod.devScript";
    static final String SHOT_DIR_PROPERTY = "jbp.lod.devShotDir";
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");

    private record Step(int tick, String action, String[] args) {
    }

    private static List<Step> steps;
    private static int cursor;
    private static int ticks;
    private static boolean started;
    private static boolean finished;
    private static double originX;
    private static double originY;
    private static double originZ;
    private static String source;

    private DevScript() {
    }

    /** Registers the tick listener when the dev property is present; otherwise does nothing. */
    public static void register() {
        String spec = System.getProperty(PROPERTY);
        if (spec == null || spec.isBlank()) {
            return;
        }
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            LOGGER.warn("[JBP-LOD] devScript: ignored outside a Fabric development environment.");
            return;
        }
        try {
            steps = parse(spec);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[JBP-LOD] devScript: could not parse the script; dev automation disabled.", failure);
            return;
        }
        LOGGER.info("[JBP-LOD] devScript: loaded {} steps from {}", steps.size(), source);
        ClientTickEvents.END_CLIENT_TICK.register(DevScript::tick);
    }

    private static List<Step> parse(String spec) throws IOException {
        List<String> lines = new ArrayList<>();
        Path path = null;
        try {
            path = Path.of(spec);
        } catch (RuntimeException ignored) {
            //Not a path (for example an inline spec with '|'); parse as inline text.
        }
        if (path != null && Files.isRegularFile(path)) {
            source = path.toString();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lines.add(line);
            }
        } else {
            source = "inline";
            for (String part : spec.split("[;\\n]")) {
                lines.add(part);
            }
        }
        List<Step> parsed = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int split = 0;
            while (split < line.length() && !Character.isWhitespace(line.charAt(split))) {
                split++;
            }
            String tickText = line.substring(0, split);
            if (tickText.endsWith(":")) {
                tickText = tickText.substring(0, tickText.length() - 1);
            }
            int tick = Integer.parseInt(tickText);
            String rest = line.substring(split).trim();
            if (rest.isEmpty()) {
                throw new IllegalArgumentException("step has no action: " + line);
            }
            int actionEnd = 0;
            while (actionEnd < rest.length() && !Character.isWhitespace(rest.charAt(actionEnd))) {
                actionEnd++;
            }
            String action = rest.substring(0, actionEnd).toLowerCase(Locale.ROOT);
            String argText = rest.substring(actionEnd).trim();
            String[] args = argText.isEmpty() ? new String[0] : argText.split("\\s+");
            parsed.add(new Step(tick, action, args));
        }
        parsed.sort((a, b) -> Integer.compare(a.tick(), b.tick()));
        return parsed;
    }

    private static void tick(Minecraft client) {
        if (finished) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (client.level == null || client.player == null || server == null) {
            return;
        }
        if (JbpLodClient.activeRuntime().isEmpty()) {
            return;
        }
        if (!started) {
            started = true;
            originX = client.player.getX();
            originY = client.player.getY();
            originZ = client.player.getZ();
            LOGGER.info("[JBP-LOD] devScript: ready at {} tick(s); origin=({}, {})", ticks,
                    String.format(Locale.ROOT, "%.2f", originX), String.format(Locale.ROOT, "%.2f", originZ));
        }
        ticks++;
        while (cursor < steps.size() && steps.get(cursor).tick() <= ticks) {
            Step step = steps.get(cursor++);
            try {
                run(client, server, step);
            } catch (RuntimeException failure) {
                LOGGER.warn("[JBP-LOD] devScript: step '" + step.action() + "' failed at tick " + step.tick(),
                        failure);
            }
        }
        if (cursor >= steps.size()) {
            finished = true;
            LOGGER.info("[JBP-LOD] devScript: all {} steps executed at tick {}", steps.size(), ticks);
        }
    }

    private static void run(Minecraft client, MinecraftServer server, Step step) {
        String[] args = step.args();
        switch (step.action()) {
            case "log" -> LOGGER.info("[JBP-LOD] devScript: tick={} {}", ticks, String.join(" ", args));
            case "screen" -> LOGGER.info("[JBP-LOD] devScript: tick={} screen={} overlay={}", ticks,
                    client.gui.screen() == null ? "null" : client.gui.screen().getClass().getName(),
                    client.gui.overlay() == null ? "null" : client.gui.overlay().getClass().getName());
            case "closegui" -> {
                String open = client.gui.screen() == null ? "null" : client.gui.screen().getClass().getName();
                client.gui.setScreen(null);
                LOGGER.info("[JBP-LOD] devScript: tick={} closed screen {}", ticks, open);
            }
            case "stats" -> JbpLodClient.activeRuntime().ifPresent(runtime ->
                    LOGGER.info("[JBP-LOD] devScript: tick={} stats: {}", ticks, runtime.session().stats().format()));
            case "cmd" -> {
                String command = String.join(" ", args);
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
                ServerPlayer player = singlePlayer(server);
                if (player != null) {
                    source = source.withEntity(player).withPosition(player.position()).withLevel(player.level());
                }
                server.getCommands().performPrefixedCommand(source, command);
                LOGGER.info("[JBP-LOD] devScript: tick={} cmd '{}'", ticks, command);
            }
            case "rd" -> {
                int distance = Integer.parseInt(args[0]);
                client.options.renderDistance().set(distance);
                //Persist + broadcast the new ClientInformation so the integrated server changes its view distance.
                client.options.save();
                client.options.broadcastOptions();
                LOGGER.info("[JBP-LOD] devScript: tick={} renderDistance={} effective={}", ticks, distance,
                        client.options.getEffectiveRenderDistance());
            }
            case "tp" -> teleport(client, server, args, false);
            case "tprel" -> teleport(client, server, args, true);
            case "look" -> {
                float yaw = Float.parseFloat(args[0]);
                float pitch = Float.parseFloat(args[1]);
                client.player.setYRot(yaw);
                client.player.setYHeadRot(yaw);
                client.player.setXRot(pitch);
                applyServerRotation(server, yaw, pitch);
                LOGGER.info("[JBP-LOD] devScript: tick={} look yaw={} pitch={}", ticks, yaw, pitch);
            }
            case "fly" -> {
                boolean on = !"off".equalsIgnoreCase(args[0]) && !"false".equalsIgnoreCase(args[0]);
                ServerPlayer player = singlePlayer(server);
                if (player != null) {
                    player.getAbilities().mayfly = true;
                    player.getAbilities().flying = on;
                    player.onUpdateAbilities();
                }
                client.player.getAbilities().mayfly = true;
                client.player.getAbilities().flying = on;
                LOGGER.info("[JBP-LOD] devScript: tick={} fly={}", ticks, on ? "on" : "off");
            }
            case "draw" -> {
                boolean on = !"off".equalsIgnoreCase(args[0]) && !"false".equalsIgnoreCase(args[0]);
                VulkanLodRenderer.setDevDrawEnabled(on);
                LOGGER.info("[JBP-LOD] devScript: tick={} lodDraw={}", ticks, on ? "on" : "off");
            }
            case "shot" -> screenshot(client, step, args);
            case "quit" -> {
                LOGGER.info("[JBP-LOD] devScript: tick={} stopping the client", ticks);
                finished = true;
                client.stop();
            }
            default -> LOGGER.warn("[JBP-LOD] devScript: unknown action '{}'", step.action());
        }
    }

    private static void teleport(Minecraft client, MinecraftServer server, String[] args, boolean relative) {
        ServerPlayer player = singlePlayer(server);
        if (player == null) {
            LOGGER.warn("[JBP-LOD] devScript: teleport skipped; no server player");
            return;
        }
        ServerLevel level = player.level();
        double baseX = relative ? originX : 0.0;
        double baseZ = relative ? originZ : 0.0;
        double x = baseX + Double.parseDouble(args[0]);
        double z = baseZ + Double.parseDouble(args[2]);
        double y;
        if ("auto".equalsIgnoreCase(args[1]) || "surface".equalsIgnoreCase(args[1])
                || args[1].toLowerCase(Locale.ROOT).startsWith("above:")) {
            //A heightmap query on an unloaded chunk returns the minimum build height; load the target chunk
            //first so a teleport into new terrain lands on the real surface instead of in the void.
            level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            double lift = args[1].toLowerCase(Locale.ROOT).startsWith("above:")
                    ? Double.parseDouble(args[1].substring("above:".length())) : 2.0;
            y = level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z)) + lift;
        } else if ("~".equals(args[1])) {
            y = player.getY();
        } else {
            y = (relative ? originY : 0.0) + Double.parseDouble(args[1]);
        }
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (args.length >= 5) {
            yaw = Float.parseFloat(args[3]);
            pitch = Float.parseFloat(args[4]);
        }
        player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, false);
        //The server only sends the camera rotation on the next move; set the local camera immediately.
        client.player.setYRot(yaw);
        client.player.setYHeadRot(yaw);
        client.player.setXRot(pitch);
        LOGGER.info("[JBP-LOD] devScript: tick={} teleported to ({}, {}, {}) yaw={} pitch={}", ticks,
                String.format(Locale.ROOT, "%.2f", x), String.format(Locale.ROOT, "%.2f", y),
                String.format(Locale.ROOT, "%.2f", z), yaw, pitch);
    }

    private static void applyServerRotation(MinecraftServer server, float yaw, float pitch) {
        ServerPlayer player = singlePlayer(server);
        if (player == null) {
            return;
        }
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
    }

    private static ServerPlayer singlePlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayers().isEmpty() ? null
                : server.getPlayerList().getPlayers().get(0);
    }

    private static void screenshot(Minecraft client, Step step, String[] args) {
        if (args.length == 0) {
            LOGGER.warn("[JBP-LOD] devScript: shot needs a name");
            return;
        }
        String name = args[0].endsWith(".png") ? args[0] : args[0] + ".png";
        Path directory = Path.of(System.getProperty(SHOT_DIR_PROPERTY, "/tmp/jbp-lod-shots"));
        Path target = directory.resolve(name);
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            LOGGER.warn("[JBP-LOD] devScript: could not create screenshot directory " + directory, failure);
            return;
        }
        //The game's own backend-agnostic readback path (RenderPearl copyTextureToBuffer) captured after this
        //tick's frame; the same path as the F2 screenshot key, so it works for the Vulkan backend.
        LOGGER.info("[JBP-LOD] devScript: tick={} shot requested {} (screen={})", ticks, target,
                client.gui.screen() == null ? "null" : client.gui.screen().getClass().getSimpleName());
        File file = target.toFile();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), 1, image -> {
            try {
                image.writeToFile(file);
                LOGGER.info("[JBP-LOD] devScript: screenshot {} {}x{}", target, image.getWidth(), image.getHeight());
            } catch (IOException failure) {
                LOGGER.warn("[JBP-LOD] devScript: screenshot write failed for " + target, failure);
            } finally {
                image.close();
            }
        });
    }
}
