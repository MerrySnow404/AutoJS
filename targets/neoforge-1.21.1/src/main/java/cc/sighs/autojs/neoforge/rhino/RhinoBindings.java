package cc.sighs.autojs.neoforge.rhino;

import cc.sighs.autojs.neoforge.script.AutoJSTools;
import cc.sighs.autojs.neoforge.script.ScriptOutput;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The globals AutoJS hangs on a Rhino scope.
 *
 * <p>Deliberately short: this mode reproduces what AutoJS itself needs — the {@code AutoJS} helpers, a place
 * for a snippet to print, and a way to reach a class by name — not KubeJS's pre-registered surface
 * ({@code Item}, {@code Text}, {@code NBT}, the event groups). A snippet written against those falls back to
 * {@code Java.loadClass} here.
 */
public final class RhinoBindings {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Rhino");

    private RhinoBindings() {
    }

    /**
     * Hangs the globals on a freshly built scope. Runs on the game thread that owns the scope, because the
     * globals below hold on to the live context.
     */
    public static void install(Context context, ScriptableObject scope, EvalSide side, List<String> diagnostics) {
        var console = new Console(diagnostics);
        var java = new JavaNamespace(context, scope);

        // addToScope rather than putProperty: it is what KubeJS's own binding registry does, and it is what
        // turns the Java objects below into the wrappers a script can call methods on.
        context.addToScope(scope, "AutoJS", new AutoJSTools(side, console));
        context.addToScope(scope, "console", console);
        context.addToScope(scope, "Java", java);

        // KubeJS scripts know JSON as `Json`, so the name is kept. It is the engine's own JSON object rather
        // than a gson-backed one: gson would hand a snippet Java maps and lists wrapped as foreign objects,
        // while this is the parser and stringifier a plain script already expects.
        context.addToScope(scope, "Json", scope.get(context, "JSON", scope));
    }

    /**
     * Where a snippet's output goes.
     *
     * <p>It is both the {@code console} global and the outlet {@link AutoJSTools} logs through, so
     * {@code console.info(...)} and {@code AutoJS.log(...)} land in the same place: the diagnostics of the eval
     * that is running, plus the game log, which is the only place left to look once that eval is over.
     */
    public static final class Console implements ScriptOutput {
        private final List<String> diagnostics;

        Console(List<String> diagnostics) {
            this.diagnostics = diagnostics;
        }

        /** What {@code AutoJS.log(...)} ends up calling. */
        @Override
        public void info(Object message) {
            var text = String.valueOf(message);
            diagnostics.add(text);
            LOGGER.info("{}", text);
        }

        public void log(Object... messages) {
            info(join(messages));
        }

        public void warn(Object... messages) {
            var text = join(messages);
            diagnostics.add(text);
            LOGGER.warn("{}", text);
        }

        public void error(Object... messages) {
            var text = join(messages);
            diagnostics.add(text);
            LOGGER.error("{}", text);
        }

        private static String join(Object[] messages) {
            var builder = new StringBuilder();
            for (var index = 0; index < messages.length; index++) {
                if (index > 0) {
                    builder.append(' ');
                }
                builder.append(messages[index]);
            }
            return builder.toString();
        }
    }

    /**
     * The {@code Java} global, the one piece of KubeJS's surface worth reproducing: without it a snippet has no
     * way to reach a class by name, because this mode registers no wrappers at all.
     */
    public static final class JavaNamespace {
        private final Context context;
        private final Scriptable scope;

        JavaNamespace(Context context, Scriptable scope) {
            this.context = context;
            this.scope = scope;
        }

        /** Loads a class and hands back its script wrapper, the way KubeJS's {@code Java.loadClass} does. */
        public Object loadClass(String name) {
            try {
                return new NativeJavaClass(context, scope, Class.forName(name));
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("no such class: " + name);
            }
        }
    }
}
