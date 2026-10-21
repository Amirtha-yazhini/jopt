package jopt.io;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.*;

/** Size measurements and javap-style listings of method code. */
public final class Bytecode {
    private Bytecode() {}

    /** Number of real instructions (labels, line numbers and frames excluded). */
    public static int instructions(InsnList code) {
        int n = 0;
        for (AbstractInsnNode i : code) if (i.getOpcode() >= 0) n++;
        return n;
    }

    /**
     * Size of the encoded code array in bytes, matching what ASM emits (it picks the short
     * {@code iload_0}-style forms). Exact except that an ldc is assumed not to need {@code ldc_w}.
     */
    public static int bytes(InsnList code) {
        int off = 0;
        for (AbstractInsnNode i : code) {
            int op = i.getOpcode();
            if (op < 0) continue;
            off += switch (i) {
                case IntInsnNode n -> op == SIPUSH ? 3 : 2;
                case VarInsnNode v -> v.var <= 3 ? 1 : v.var <= 255 ? 2 : 4;
                case IincInsnNode n -> n.var > 255 || n.incr < Byte.MIN_VALUE || n.incr > Byte.MAX_VALUE ? 6 : 3;
                case LdcInsnNode l -> l.cst instanceof Long || l.cst instanceof Double ? 3 : 2;
                case TableSwitchInsnNode t -> 1 + pad(off) + 12 + 4 * t.labels.size();
                case LookupSwitchInsnNode l -> 1 + pad(off) + 8 + 8 * l.labels.size();
                default -> switch (i.getType()) {
                    case AbstractInsnNode.INSN -> 1;
                    case AbstractInsnNode.METHOD_INSN -> op == INVOKEINTERFACE ? 5 : 3;
                    case AbstractInsnNode.INVOKE_DYNAMIC_INSN -> 5;
                    case AbstractInsnNode.MULTIANEWARRAY_INSN -> 4;
                    default -> 3; // field, type, jump
                };
            };
        }
        return off;
    }

    private static int pad(int offset) {
        return (4 - ((offset + 1) % 4)) % 4;
    }

    /** Disassembly of a method's code (no frames), one instruction per line. */
    public static String listing(MethodNode m) {
        Textifier t = new Textifier();
        TraceMethodVisitor v = new TraceMethodVisitor(t);
        for (TryCatchBlockNode tc : m.tryCatchBlocks) tc.accept(v);
        m.instructions.accept(v);
        StringWriter sw = new StringWriter();
        t.print(new PrintWriter(sw));
        List<String> lines = new java.util.ArrayList<>();
        boolean inBootstrapArgs = false;
        for (String l : sw.toString().split("\n")) {
            l = l.stripTrailing();
            if (inBootstrapArgs) { // invokedynamic's bootstrap method and arguments span many lines
                inBootstrapArgs = !l.strip().equals("]");
                continue;
            }
            if (l.isBlank() || l.strip().startsWith("FRAME") || l.strip().startsWith("LINENUMBER")) continue;
            if (l.contains("INVOKEDYNAMIC") && l.endsWith("[")) {
                inBootstrapArgs = true;
                l = l.substring(0, l.length() - 1).stripTrailing();
            }
            lines.add(l);
        }
        // Show only labels something refers to (jumps, switches, exception ranges); renumber them.
        Set<String> used = new HashSet<>();
        for (String l : lines) {
            if (LABEL_DEF.matcher(l).matches()) continue;
            Matcher mt = LABEL_REF.matcher(l);
            while (mt.find()) used.add(mt.group());
        }
        Map<String, String> renamed = new HashMap<>();
        for (String l : lines) {
            String s = l.strip();
            if (LABEL_DEF.matcher(l).matches() && used.contains(s)) renamed.put(s, "L" + renamed.size());
        }
        StringBuilder out = new StringBuilder();
        for (String l : lines) {
            if (LABEL_DEF.matcher(l).matches()) {
                if (!used.contains(l.strip())) continue;
                out.append(renamed.get(l.strip())).append(':');
            } else {
                String body = l.startsWith("    ") ? l.substring(4) : l;
                out.append("  ").append(LABEL_REF.matcher(body).replaceAll(r -> renamed.getOrDefault(r.group(), r.group())));
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static final Pattern LABEL_DEF = Pattern.compile("\\s*L\\d+");
    private static final Pattern LABEL_REF = Pattern.compile("\\bL\\d+\\b");
}
