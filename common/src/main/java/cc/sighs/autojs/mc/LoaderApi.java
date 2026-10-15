package cc.sighs.autojs.mc;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;

/**
 * What the loader — NeoForge, Forge or Fabric — knows about the running game.
 *
 * <p>The mod list, the distribution, the game directory and the server that is currently running are the four
 * things shared code used to read straight out of {@code ModList}, {@code FMLEnvironment}, {@code FMLPaths} and
 * {@code ServerLifecycleHooks}. Those are the same calls on NeoForge and Forge and a completely different API on
 * Fabric, and the package name carries the loader, so naming them in a shared file pins the whole tree to one
 * loader. Every method here is a question a target answers with its own loader's API.
 *
 * <p>The answers are the loader's own state, so an implementation is stateless and reads it on every call: the
 * mod list is fixed at launch, but the running server is not, and the game directory has to be the one the
 * loader resolved rather than anything cached here.
 */
public interface LoaderApi {
    /**
     * The id of every loaded mod, in the loader's own order.
     *
     * <p>Unsorted on purpose: sorting is what the caller prints, and keeping it in one place is what keeps
     * {@code AutoJS.mods()} the same list on every loader.
     */
    List<String> modIds();

    /** The version of a mod, or null when no mod with that id is loaded. */
    String modVersion(String modId);

    /**
     * The mod that ships a class, or null for a vanilla class and for anything loaded from a development
     * workspace.
     *
     * <p>Finding this out means comparing the class's code source against the mod files, and the locator that
     * maps one onto the other is loader-specific — which is exactly why it cannot live in the shared tree.
     */
    String owningMod(Class<?> type);

    /** The distribution this process runs as: {@code CLIENT} or {@code DEDICATED_SERVER}. */
    String distName();

    /** The game directory: where a script's relative paths start. */
    Path gameDir();

    /** The server that is running, or null when none is. */
    MinecraftServer currentServer();
}
