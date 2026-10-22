package jopt.playground;

import jopt.io.Bytecode.Row;
import jopt.ir.Block;
import jopt.ir.Handler;
import jopt.ir.Insn;
import jopt.ir.MethodIR;
import jopt.ir.Terminator;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.Printer;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Text form of a {@link MethodIR}: one header per basic block, one row per instruction, and the
 * terminator. Each row carries the source line of the nearest preceding {@code Insn.Line} marker.
 */
public final class IRPrinter {
    private IRPrinter() {}

    public static List<Row> print(MethodIR ir) {
        List<Row> rows = new ArrayList<>();
        for (Block b : ir.blocks) {
            int line = -1;
            rows.add(new Row(header(b, b == ir.entry()), -1));
            for (Insn x : b.insns) {
                if (x instanceof Insn.Line l) {
                    line = l.line();
                    continue;
                }
                rows.add(new Row("  " + insn(x), line));
            }
            rows.add(new Row("  " + terminator(b.term), line));
        }
        return rows;
    }

    private static String header(Block b, boolean entry) {
        StringBuilder h = new StringBuilder(b.toString()).append(':');
        if (entry) h.append("  (entry)");
        if (!b.handlers.isEmpty()) {
            StringJoiner j = new StringJoiner(", ", "  try { catch ", " }");
            for (Handler hd : b.handlers) j.add(hd.toString());
            h.append(j);
        }
        return h.toString();
    }

    static String insn(Insn x) {
        return switch (x) {
            case Insn.Simple s -> op(s.opcode());
            case Insn.Const c -> "CONST " + constant(c.value());
            case Insn.Load l -> op(l.opcode()) + " " + l.var();
            case Insn.Store s -> op(s.opcode()) + " " + s.var();
            case Insn.Iinc i -> "IINC " + i.var() + " " + i.increment();
            case Insn.Field f -> op(f.opcode()) + " " + f.owner() + "." + f.name() + " : " + f.desc();
            case Insn.Invoke m -> op(m.opcode()) + " " + m.owner() + "." + m.name() + " " + m.desc();
            case Insn.Indy d -> "INVOKEDYNAMIC " + d.name() + " " + d.desc();
            case Insn.TypeOp t -> op(t.opcode()) + " " + t.type();
            case Insn.NewArray n -> "NEWARRAY " + Printer.TYPES[n.atype()];
            case Insn.MultiNewArray m -> "MULTIANEWARRAY " + m.desc() + " " + m.dims();
            case Insn.Line l -> "LINE " + l.line();
        };
    }

    static String terminator(Terminator t) {
        return switch (t) {
            case Terminator.Goto g -> "GOTO " + g.target();
            case Terminator.If f -> op(f.opcode()) + " " + f.target() + " else " + f.next();
            case Terminator.Switch s -> {
                StringJoiner j = new StringJoiner(", ", "SWITCH { ", " }");
                for (int i = 0; i < s.keys().length; i++) j.add(s.keys()[i] + ": " + s.targets()[i]);
                j.add("default: " + s.dflt());
                yield j.toString();
            }
            case Terminator.Return r -> op(r.opcode());
            case Terminator.Throw th -> "ATHROW";
            case null -> "(falls off the end)";
        };
    }

    private static String op(int opcode) {
        return Printer.OPCODES[opcode];
    }

    /** Java-like literal for a constant push. */
    static String constant(Object v) {
        return switch (v) {
            case null -> "null";
            case Integer i -> i.toString();
            case Long l -> l + "L";
            case Float f -> f + "f";
            case Double d -> d + "d";
            case String s -> quote(s);
            case Type t -> t.getSort() == Type.METHOD ? t.getDescriptor() : t.getClassName() + ".class";
            default -> v.toString();
        };
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
