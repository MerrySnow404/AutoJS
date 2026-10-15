package cc.sighs.autojs.neoforge.rhino;

import dev.latvian.mods.rhino.Context;

import java.util.function.Supplier;

/**
 * Runs work with a Rhino context associated with the current thread, and takes it back off afterwards.
 *
 * <p>This exists because of one thing 1802 does that 1902 does not: a context is bound to a thread rather than
 * built per call. {@code Context.enter()} consults a {@code VMBridge} thread-local, and
 * {@code Context.getContext()} throws {@code "No Context associated with current Thread"} when there is none —
 * a call several Rhino internals make, including {@code Interpreter} when it captures an exception's script
 * stack. Evaluating with a context that is not associated with the calling thread therefore works most of the
 * time and fails on the error paths.
 *
 * <p>KubeJS is in the same position and does the same thing: {@code ScriptManager.load()} enters a context for
 * the duration of a load and exits it at the end, which is why the context a script file ran in has an
 * {@code enterCount} of zero again by the time anything else looks at it.
 *
 * <p>Entering is therefore scoped rather than permanent: leaving the association behind would make the next
 * {@code Context.enter()} on this thread — KubeJS's own, during a reload — hand back <em>this</em> context
 * instead of a fresh one.
 *
 * <p>Two cases do not enter, and neither is an error: when the thread already has a context, entering ours is
 * impossible ({@code Context.enter} refuses one that is associated elsewhere, and returns the existing one
 * otherwise), so the work runs as the caller set it up; and when the context itself is held by another thread,
 * the same refusal is caught and the work runs unassociated, which is no worse than what 1902's Rhino did on
 * every call.
 */
public final class RhinoContexts {
    private RhinoContexts() {
    }

    /** The work's result, with {@code context} associated with this thread for the duration. */
    public static <T> T with(Context context, Supplier<T> work) {
        if (Context.getCurrentContext() != null || !enter(context)) {
            return work.get();
        }
        try {
            return work.get();
        } finally {
            Context.exit();
        }
    }

    /**
     * Associates {@code context} with this thread.
     *
     * <p>{@code ContextFactory.enterContext} is the public door to the package-private {@code Context.enter},
     * and it needs the context's own factory: that is the one the context was made by, and the one
     * {@code Context.exit} releases it back to.
     */
    private static boolean enter(Context context) {
        try {
            context.getFactory().enterContext(context);
            return true;
        } catch (RuntimeException e) {
            // "can not use Context instance already associated with some thread": the context is in use
            // elsewhere right now. Evaluating without the association is what a 1902 kernel always did.
            return false;
        }
    }
}
