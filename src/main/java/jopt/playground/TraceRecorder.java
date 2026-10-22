package jopt.playground;

import jopt.io.Bytecode;
import jopt.io.Bytecode.Row;
import jopt.ir.IRWriter;
import jopt.ir.MethodIR;
import jopt.opt.Counters;
import jopt.opt.OptimizerTrace;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records a snapshot of each method after every step of the real optimizer run: the IR as text, the
 * IR lowered to bytecode by jopt's own {@link IRWriter}, and measured sizes.
 */
final class TraceRecorder implements OptimizerTrace {
    static final String CLASS_UNCHANGED = "CLASS_UNCHANGED";

    private final int maxMethods;
    private final List<Recording> recordings = new ArrayList<>();
    private final Map<String, String> unchangedClasses = new HashMap<>();
    private int notTraced;

    TraceRecorder(int maxMethods) {
        this.maxMethods = maxMethods;
    }

    @Override
    public MethodTrace method(String owner, MethodNode original) {
        if (recordings.size() >= maxMethods) {
            notTraced++;
            return null;
        }
        Recording r = new Recording(owner, original);
        recordings.add(r);
        return r;
    }

    @Override
    public void classUnchanged(String owner, String reason) {
        unchangedClasses.put(owner, reason);
    }

    List<Model.MethodTrace> traces() {
        return recordings.stream().map(r -> r.toModel(unchangedClasses.get(r.owner))).toList();
    }

    int notTraced() {
        return notTraced;
    }

    private static final class Recording implements MethodTrace {
        private final String owner;
        private final MethodNode original;
        private final Model.Snapshot input;
        private final List<Model.Step> steps = new ArrayList<>();
        private final Counters total = new Counters();
        private List<Row> lastIr;
        private int rounds;
        private Outcome outcome;
        private String detail;
        private Model.Snapshot result;

        Recording(String owner, MethodNode original) {
            this.owner = owner;
            this.original = original;
            // Taken now: the optimizer replaces the method's instructions once it accepts the result.
            this.input = new Model.Snapshot(null, Bytecode.rows(original), null,
                    Bytecode.instructions(original.instructions), Bytecode.bytes(original.instructions), null, null);
            steps.add(new Model.Step(0, "input", 0, null, 0, Map.of(), false, input));
        }

        @Override
        public void irBuilt(MethodIR ir) {
            add("build", 0, null, 0, Map.of(), ir);
        }

        @Override
        public void passRan(int round, String pass, int changes, Counters what, MethodIR ir) {
            rounds = Math.max(rounds, round);
            total.addAll(what);
            add("pass", round, pass, changes, Collections.unmodifiableMap(new LinkedHashMap<>(what.asMap())), ir);
        }

        @Override
        public void finished(Outcome outcome, String detail, MethodNode lowered) {
            this.outcome = outcome;
            this.detail = detail;
            if (outcome == Outcome.REPLACED && lowered != null) result = snapshotOf(lastIr, lowered);
        }

        private void add(String kind, int round, String pass, int changes, Map<String, Long> counters, MethodIR ir) {
            List<Row> irRows = IRPrinter.print(ir);
            boolean same = irRows.equals(lastIr);
            Model.Snapshot snap = same ? null : lower(ir, irRows);
            lastIr = irRows;
            steps.add(new Model.Step(steps.size(), kind, round, pass, changes, counters, same, snap));
        }

        /** Lowers the current IR with the real writer; intermediate IR that cannot be lowered is reported as such. */
        private Model.Snapshot lower(MethodIR ir, List<Row> irRows) {
            int blocks = ir.blocks.size(), irInsns = ir.instructionCount();
            try {
                MethodNode m = IRWriter.toMethodNode(ir, original);
                return new Model.Snapshot(irRows, Bytecode.rows(m), null, Bytecode.instructions(m.instructions),
                        Bytecode.bytes(m.instructions), blocks, irInsns);
            } catch (RuntimeException e) {
                return new Model.Snapshot(irRows, null, String.valueOf(e.getMessage()), null, null, blocks, irInsns);
            }
        }

        private static Model.Snapshot snapshotOf(List<Row> ir, MethodNode m) {
            return new Model.Snapshot(ir, Bytecode.rows(m), null, Bytecode.instructions(m.instructions),
                    Bytecode.bytes(m.instructions), null, null);
        }

        Model.MethodTrace toModel(String classUnchangedReason) {
            String outcomeName = outcome == null ? Outcome.INTERNAL_ERROR.name() : outcome.name();
            String outcomeDetail = outcome == null ? "the optimizer did not report a decision" : detail;
            boolean replaced = outcome == Outcome.REPLACED && classUnchangedReason == null;
            if (outcome == Outcome.REPLACED && classUnchangedReason != null) {
                outcomeName = CLASS_UNCHANGED;
                outcomeDetail = classUnchangedReason;
            }
            // The decision step shows the code that ends up in the class file.
            Model.Snapshot kept = replaced ? result : input;
            List<Model.Step> all = new ArrayList<>(steps);
            all.add(new Model.Step(all.size(), "decision", 0, null, 0, Map.of(), false, kept));
            return new Model.MethodTrace(owner + "." + original.name + original.desc, owner, original.name,
                    original.desc, outcomeName, outcomeDetail, List.copyOf(all), rounds,
                    Collections.unmodifiableMap(new LinkedHashMap<>(total.asMap())));
        }
    }
}
