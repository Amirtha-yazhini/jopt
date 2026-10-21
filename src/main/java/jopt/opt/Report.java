package jopt.opt;

import java.util.ArrayList;
import java.util.List;

/** What an optimization run did, for the console summary and the HTML report. */
public final class Report {
    public int classes, classesChanged;
    public int methods, methodsChanged;
    public long instructionsBefore, instructionsAfter;
    public long codeBytesBefore, codeBytesAfter;
    public long fileBytesBefore, fileBytesAfter;
    public final Counters counters = new Counters();
    public final List<MethodChange> changes = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    /** One optimized method; listings are null when the report does not keep them. */
    public record MethodChange(String owner, String name, String desc,
                               int instructionsBefore, int instructionsAfter,
                               int bytesBefore, int bytesAfter,
                               String before, String after, Counters what) {
        public int bytesSaved() {
            return bytesBefore - bytesAfter;
        }

        public String displayName() {
            return owner.replace('/', '.') + "." + name + desc;
        }
    }

    /** Signed relative change, e.g. "-18.7%" or "+2.2%". */
    public static String change(long before, long after) {
        if (before == 0) return "0.0%";
        return String.format("%+.1f%%", 100.0 * (after - before) / before);
    }

    public static double percent(long before, long after) {
        return before == 0 ? 0 : 100.0 * (before - after) / before;
    }
}
