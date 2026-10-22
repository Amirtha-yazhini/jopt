package jopt.playground;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal JSON serializer for the API's response model: records (by component), maps, collections,
 * strings, numbers, booleans, enums and null. Output only; the API never parses JSON.
 */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object v, StringBuilder out) {
        switch (v) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d -> number(d, out);
            case Float f -> number(f.doubleValue(), out);
            case Enum<?> e -> string(e.name(), out);
            case Map<?, ?> m -> object(m, out);
            case Collection<?> c -> array(c, out);
            case Record r -> record(r, out);
            default -> throw new IllegalArgumentException("cannot serialize " + v.getClass().getName());
        }
    }

    private static void number(double d, StringBuilder out) {
        if (Double.isNaN(d) || Double.isInfinite(d)) out.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d);
        else out.append(d);
    }

    private static void object(Map<?, ?> m, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) out.append(',');
            first = false;
            string(String.valueOf(e.getKey()), out);
            out.append(':');
            write(e.getValue(), out);
        }
        out.append('}');
    }

    private static void array(Collection<?> c, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object o : c) {
            if (!first) out.append(',');
            first = false;
            write(o, out);
        }
        out.append(']');
    }

    private static void record(Record r, StringBuilder out) {
        out.append('{');
        RecordComponent[] parts = r.getClass().getRecordComponents();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(',');
            string(parts[i].getName(), out);
            out.append(':');
            try {
                write(parts[i].getAccessor().invoke(r), out);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("cannot read " + parts[i].getName() + " of " + r.getClass(), e);
            }
        }
        out.append('}');
    }

    static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                // Escaping < > & keeps the JSON safe even if it is ever embedded in HTML.
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
