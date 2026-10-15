package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.LoaderApi;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link LoaderApi} for Forge 1.19.2: the four FML calls {@code AutoJSTools} used to make itself.
 *
 * <p>Forge answers all four out of {@code net.minecraftforge.fml}: {@code ModList.get().getMods()},
 * {@code FMLEnvironment.dist}, {@code FMLPaths.GAMEDIR} and {@code ServerLifecycleHooks.getCurrentServer()}.
 * The class names are the same ones the NeoForge target uses — Forge's FML API is what NeoForge forked — but
 * the packages are not: this loader spells them {@code net.minecraftforge.*} where NeoForge spells them
 * {@code net.neoforged.*}, and that package difference is the whole reason this file exists twice.
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
