package cc.sighs.autojs.mc;

/**
 * Where a target installs the Minecraft and loader API its version has, and where shared code reads it.
 *
 * <p>The three interfaces it hands out are the Minecraft API axis: {@link LoaderApi} for what the loader knows,
 * {@link VanillaApi} for the vanilla API that moved between the versions AutoJS supports, and {@link ClientApi}
 * for the client-only additions. Keeping them behind one access point is what lets the shared scripts stay
 * static utilities — they ask {@code McApi.vanilla()} at the call site instead of having a target implementation
 * threaded through every signature — and it is the same shape as {@code EvalBackends} and
 * {@code AutoJSTools.setClientBridge}: a target installs its implementation, everything else reads it.
 *
 * <p>Installation happens once, from the mod constructor, before any script can run. The client half is
 * installed separately, from the client entry point, so that a dedicated server never touches it.
 *
 * <p>Nothing here reaches for a fallback: a target that forgot to install gets an exception naming what is
 * missing at the first call, rather than a null that turns into a confusing failure somewhere else.
 */
public final class McApi {
    private static volatile LoaderApi loader;
    private static volatile VanillaApi vanilla;
    private static volatile ClientApi client;

    private McApi() {
    }

    /** Called once by a target's mod constructor. */
    public static void install(LoaderApi loaderApi, VanillaApi vanillaApi) {
        loader = loaderApi;
        vanilla = vanillaApi;
    }

    /** Called once by a target's client entry point; never on a dedicated server. */
    public static void installClient(ClientApi clientApi) {
        client = clientApi;
    }

    /** The loader API this target installed. */
    public static LoaderApi loader() {
        return require(loader, "loader API");
    }

    /** The vanilla API this target installed. */
    public static VanillaApi vanilla() {
        return require(vanilla, "vanilla API");
    }

    /** The client API this target's client entry point installed. */
    public static ClientApi client() {
        return require(client, "client API");
    }

    /** The installed implementation, or a failure naming it. */
    private static <T> T require(T api, String what) {
        if (api == null) {
            throw new IllegalStateException("no " + what + " has been installed: a target must call "
                    + "McApi.install() from its mod constructor, and McApi.installClient() from its client entry "
                    + "point, before anything runs a script");
        }
        return api;
    }
}
