package jopt.ir;

/**
 * An exception handler: entry block plus caught type (internal name, or null for "any", as used by
 * finally). Blocks reference the handlers that cover them; identity-compared.
 */
public final class Handler {
    public final String type;
    public final Block target;

    public Handler(String type, Block target) {
        this.type = type;
        this.target = target;
    }

    @Override
    public String toString() {
        return (type == null ? "any" : type) + " -> " + target;
    }
}
