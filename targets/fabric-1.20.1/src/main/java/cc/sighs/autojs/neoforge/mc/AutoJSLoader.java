package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.LoaderApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link LoaderApi} for Fabric 1.20.1: the four questions the shared code asks, answered by the Fabric loader.
 *
 * <p>Fabric's loader API is a different shape from Forge's, not a rename of it: one {@code FabricLoader}
 * instance answers all of them, the mod list is a {@code Collection<ModContainer>} keyed by a
 * {@code ModMetadata}, the distribution is an {@link EnvType} enum whose two constants are not the words
 * {@link LoaderApi} promises, and the config directory is a sibling accessor of the game directory rather than
 * a constant. That is the whole reason this file exists twice.
 *
 * <p>One answer is not the loader's at all. Fabric has no equivalent of Forge's
 * {@code ServerLifecycleHooks.getCurrentServer()}, so the running server is remembered from the lifecycle
 * event that announced it — {@code AutoJSFabric} calls {@link #setCurrentServer(MinecraftServer)} from
 * {@code ServerLifecycleEvents.SERVER_STARTED} and clears it from {@code SERVER_STOPPING}, which is exactly
 * what Forge's hook does internally. Nothing else here is stateful.
 *
 * <p>Rhino, and therefore this target's compile classpath, never mentions Minecraft: Rhino is a
 * loader-agnostic artifact here, which is why nothing in this file has to reach it.
 */
public final class AutoJSLoader implements LoaderApi {
    /** Stateless apart from the server below: every other answer is a read of the loader's own state. */
    public static final AutoJSLoader INSTANCE = new AutoJSLoader();

    /** The server the loader is running, announced by Fabric's server lifecycle events. */
    private static volatile MinecraftServer currentServer;

    private AutoJSLoader() {
    }

    /**
     * Remembers the server that just started, or forgets the one that stopped.
     *
     * <p>Called by the mod entry point from Fabric's server lifecycle events, because Fabric offers no
     * question this class could ask instead.
     */
    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }

    @Override
    public List<String> modIds() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId())
                .toList();
    }

    @Override
    public String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
    }

    /**
     * The mod whose file contains the class, found by matching the class's code source against each mod's
     * origin paths.
     *
     * <p>Fabric publishes no class-to-mod lookup, so the code source is the only handle there is; its
     * paths are compared absolutely because a mod origin may carry a game-directory-relative path. A missing
     * code source is normal in a development workspace, where classes come from a build directory that belongs
     * to no mod, so the whole search is best-effort and answers null rather than failing.
     */
    @Override
    public String owningMod(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }

            var location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            for (var mod : FabricLoader.getInstance().getAllMods()) {
                for (var path : mod.getOrigin().getPaths()) {
                    if (path.toAbsolutePath().normalize().equals(location)) {
                        return mod.getMetadata().getId();
                    }
                }
            }
        } catch (Exception ignored) {
            // A missing or unreadable code source is normal in a development workspace.
        }
        return null;
    }

    /**
     * This process's distribution, in the words {@link LoaderApi} promises.
     *
     * <p>Fabric's own spelling is {@code CLIENT}/{@code SERVER}, which is not what a script sees: the shared
     * code reports this string as {@code snapshot().dist}, so the loader's {@code SERVER} is named
     * {@code DEDICATED_SERVER} here to read the same on every loader.
     */
    @Override
    public String distName() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT ? "CLIENT" : "DEDICATED_SERVER";
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public MinecraftServer currentServer() {
        return currentServer;
    }
}
