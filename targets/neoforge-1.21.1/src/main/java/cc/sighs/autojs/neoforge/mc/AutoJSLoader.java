package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.LoaderApi;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link LoaderApi} for NeoForge 1.21.1: the four FML calls {@code AutoJSTools} used to make itself.
 *
 * <p>{@code ModList.get().getMods()}, {@code FMLEnvironment.dist}, {@code FMLPaths.GAMEDIR} and
 * {@code ServerLifecycleHooks.getCurrentServer()} are what "which mods are loaded, what am I, where is the game
 * directory, which server is up" means on this loader. Forge spells the same four calls in its own packages, and
 * Fabric has no equivalent of any of them, which is why they are answered here rather than in {@code common}.
 */
public final class AutoJSLoader implements LoaderApi {
    /** Stateless: every answer is a read of the loader's own state. */
    public static final AutoJSLoader INSTANCE = new AutoJSLoader();

    private AutoJSLoader() {
    }

    @Override
    public List<String> modIds() {
        return ModList.get().getMods().stream().map(info -> info.getModId()).toList();
    }

    @Override
    public String modVersion(String modId) {
        for (var info : ModList.get().getMods()) {
            if (info.getModId().equals(modId)) {
                return String.valueOf(info.getVersion());
            }
        }
        return null;
    }

    /**
     * The mod whose file matches the class's code source.
     *
     * <p>A missing or unreadable code source is normal in a development workspace, so the whole search is
     * best-effort and answers null rather than failing.
     */
    @Override
    public String owningMod(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }

            var location = source.getLocation().toURI();
            for (var info : ModList.get().getMods()) {
                var file = info.getOwningFile().getFile().getFilePath();
                if (file != null && file.toUri().equals(location)) {
                    return info.getModId();
                }
            }
        } catch (Exception ignored) {
            // A missing or unreadable code source is normal in a development workspace.
        }
        return null;
    }

    @Override
    public String distName() {
        return FMLEnvironment.dist.name();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public MinecraftServer currentServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }
}
