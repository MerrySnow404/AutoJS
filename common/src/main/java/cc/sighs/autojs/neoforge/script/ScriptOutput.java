package cc.sighs.autojs.neoforge.script;

/**
 * Where {@link AutoJSTools} writes what a script or a snippet logs.
 *
 * <p>An interface rather than a KubeJS console because two engines sit behind it: a KubeJS script context,
 * whose console also reaches {@code logs/kubejs/<side>.log}, and the standalone Rhino kernel, which has no
 * console beyond the one AutoJS hangs on its scope.
 */
public interface ScriptOutput {
    void info(Object message);
}
