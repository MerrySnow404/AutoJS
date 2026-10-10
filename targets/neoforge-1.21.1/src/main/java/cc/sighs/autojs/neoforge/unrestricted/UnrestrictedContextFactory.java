package cc.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.KubeJSContextFactory;
import dev.latvian.mods.rhino.util.wrap.TypeWrappers;

/**
 * Builds the unrestricted contexts, bound to KubeJS's own factory.
 *
 * <p>The contexts are constructed with the factory KubeJS just built rather than with this one, so every
 * piece of per-context state KubeJS sets up on its factory, above all the type wrappers that recipes,
 * items, text and every other conversion depend on, keeps resolving to the stock instance. This factory
 * exists only to decide which context class gets created, and everything it is asked for is forwarded.
 */
public final class UnrestrictedContextFactory extends KubeJSContextFactory {
    private final KubeJSContextFactory stock;

    public UnrestrictedContextFactory(KubeJSContextFactory stock) {
        super(stock.manager);
        this.stock = stock;
    }

    @Override
    protected KubeJSContext createContext() {
        return stock.manager.scriptType.isServer()
                ? new UnrestrictedKubeJSServerContext(stock)
                : new UnrestrictedKubeJSContext(stock);
    }

    /**
     * The stock instance's registry, not this one's: this factory never has wrappers registered into it,
     * and KubeJS's manager is replaced by something callers may still ask for wrappers on.
     */
    @Override
    public TypeWrappers getTypeWrappers() {
        return stock.getTypeWrappers();
    }
}
