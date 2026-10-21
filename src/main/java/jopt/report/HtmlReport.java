package jopt.report;

import jopt.opt.Report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** A self-contained HTML page: summary, what each optimization did, and per-method before/after code. */
public final class HtmlReport {
    private HtmlReport() {}

    private static final int MAX_METHODS = 300;

    public static void write(Path file, Report r, String input, String output) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>jopt report</title><style>
                :root{--bg:#fcfcfb;--panel:#f3f2ef;--ink:#0b0b0b;--ink2:#52514e;--line:#e4e3df;--accent:#2a78d6;
                --del:#fbe3e2;--delink:#9b1c1b;--add:#dff3e8;--addink:#13603f}
                @media (prefers-color-scheme:dark){:root{--bg:#1a1a19;--panel:#242422;--ink:#f0efec;--ink2:#c3c2b7;
                --line:#3a3936;--accent:#3987e5;--del:#4a2222;--delink:#f2b8b5;--add:#1d3b2c;--addink:#a6e3c3}}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);
                font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
                main{max-width:1200px;margin:0 auto;padding:32px 16px 64px}
                h1{font-size:26px;margin:0 0 4px}h2{font-size:18px;margin:36px 0 12px}
                .sub{color:var(--ink2);margin:0 0 24px;overflow-wrap:anywhere}
                .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}
                .tile{background:var(--panel);border-radius:10px;padding:14px 16px}
                .tile b{display:block;font-size:28px;font-variant-numeric:tabular-nums}
                .tile span{color:var(--ink2);font-size:13px}
                table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
                td,th{padding:6px 10px;border-bottom:1px solid var(--line);text-align:left}
                th{color:var(--ink2);font-weight:600;font-size:13px}td.n,th.n{text-align:right}
                details{border:1px solid var(--line);border-radius:8px;margin:8px 0;background:var(--bg)}
                summary{cursor:pointer;padding:10px 14px;display:flex;gap:12px;flex-wrap:wrap;align-items:baseline}
                summary code{font-weight:600;overflow-wrap:anywhere}summary .m{color:var(--ink2);font-size:13px}
                .saved{color:var(--accent);font-weight:600;font-size:13px}
                .cols{display:grid;grid-template-columns:1fr 1fr;gap:0;border-top:1px solid var(--line)}
                .cols>div{overflow-x:auto;padding:8px 0}.cols>div+div{border-left:1px solid var(--line)}
                .cols h3{font-size:12px;color:var(--ink2);margin:0 12px 6px;text-transform:uppercase;letter-spacing:.04em}
                pre{margin:0;font:12.5px/1.45 ui-monospace,SFMono-Regular,Consolas,monospace}
                pre span{display:block;padding:0 12px;white-space:pre}
                .d{background:var(--del);color:var(--delink)}.a{background:var(--add);color:var(--addink)}
                .what{color:var(--ink2);font-size:13px;padding:0 14px 10px}
                .warn{color:var(--ink2);font-size:13px}
                @media (max-width:700px){.cols{grid-template-columns:1fr}.cols>div+div{border-left:0;border-top:1px solid var(--line)}}
                </style></head><body><main>
                """);
        h.append("<h1>jopt optimization report</h1>");
        h.append("<p class=sub>").append(esc(input)).append(" &rarr; ").append(esc(output)).append("</p>");

        h.append("<div class=tiles>");
        tile(h, r.methodsChanged + " / " + r.methods, "methods optimized (in " + r.classesChanged + " of " + r.classes + " classes)");
        tile(h, pct(r.instructionsBefore, r.instructionsAfter), "fewer instructions (" + r.instructionsBefore + " &rarr; " + r.instructionsAfter + ")");
        tile(h, pct(r.codeBytesBefore, r.codeBytesAfter), "smaller bytecode (" + r.codeBytesBefore + " &rarr; " + r.codeBytesAfter + " B)");
        tile(h, pct(r.fileBytesBefore, r.fileBytesAfter), "smaller class files (" + r.fileBytesBefore + " &rarr; " + r.fileBytesAfter + " B)");
        h.append("</div>");

        h.append("<h2>What was done</h2><table><tr><th>Optimization</th><th class=n>Count</th></tr>");
        for (Map.Entry<String, Long> e : r.counters.asMap().entrySet())
            h.append("<tr><td>").append(esc(e.getKey())).append("</td><td class=n>").append(e.getValue()).append("</td></tr>");
        h.append("</table>");

        List<Report.MethodChange> methods = new ArrayList<>(r.changes);
        methods.sort(Comparator.comparingInt(Report.MethodChange::bytesSaved).reversed()
                .thenComparing(Report.MethodChange::displayName));
        h.append("<h2>Optimized methods</h2>");
        if (methods.size() > MAX_METHODS)
            h.append("<p class=warn>Showing the ").append(MAX_METHODS).append(" methods with the largest savings.</p>");
        int shown = 0;
        for (Report.MethodChange m : methods) {
            if (shown++ == MAX_METHODS) break;
            h.append("<details><summary><code>").append(esc(m.owner().replace('/', '.'))).append('.')
                    .append(esc(m.name())).append("</code><span class=m>").append(esc(m.desc())).append("</span>")
                    .append("<span class=saved>").append(m.bytesBefore()).append(" &rarr; ").append(m.bytesAfter())
                    .append(" bytes, ").append(m.instructionsBefore()).append(" &rarr; ").append(m.instructionsAfter())
                    .append(" instructions</span></summary>");
            h.append("<div class=what>");
            List<String> parts = new ArrayList<>();
            m.what().asMap().forEach((k, v) -> parts.add(v + "&times; " + esc(k)));
            h.append(String.join(" &middot; ", parts)).append("</div>");
            if (m.before() != null) {
                String[] diff = Diff.sideBySide(m.before(), m.after());
                h.append("<div class=cols><div><h3>Before</h3><pre>").append(diff[0]).append("</pre></div>")
                        .append("<div><h3>After</h3><pre>").append(diff[1]).append("</pre></div></div>");
            }
            h.append("</details>");
        }

        if (!r.warnings.isEmpty()) {
            h.append("<h2>Left unchanged</h2><ul class=warn>");
            for (String w : r.warnings) h.append("<li>").append(esc(w)).append("</li>");
            h.append("</ul>");
        }
        h.append("</main></body></html>\n");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, h);
    }

    private static void tile(StringBuilder h, String big, String label) {
        h.append("<div class=tile><b>").append(big).append("</b><span>").append(label).append("</span></div>");
    }

    private static String pct(long before, long after) {
        return String.format("%.1f%%", Report.percent(before, after));
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Line diff (LCS) of two listings, rendered as two columns of highlighted lines. */
    static final class Diff {
        static String[] sideBySide(String before, String after) {
            String[] a = before.split("\n"), b = after.split("\n");
            if ((long) a.length * b.length > 4_000_000) return new String[]{plain(a, ""), plain(b, "")};
            // Label names differ between the versions, so compare with them blanked out.
            String[] na = normalize(a), nb = normalize(b);
            int[][] lcs = new int[a.length + 1][b.length + 1];
            for (int i = a.length - 1; i >= 0; i--)
                for (int j = b.length - 1; j >= 0; j--)
                    lcs[i][j] = na[i].equals(nb[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            StringBuilder left = new StringBuilder(), right = new StringBuilder();
            int i = 0, j = 0;
            while (i < a.length || j < b.length) {
                if (i < a.length && j < b.length && na[i].equals(nb[j])) {
                    line(left, a[i++], "");
                    line(right, b[j++], "");
                } else if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                    line(right, b[j++], "a");
                } else {
                    line(left, a[i++], "d");
                }
            }
            return new String[]{left.toString(), right.toString()};
        }

        private static String[] normalize(String[] lines) {
            String[] out = new String[lines.length];
            for (int i = 0; i < lines.length; i++) out[i] = lines[i].replaceAll("\\bL\\d+\\b", "L").strip();
            return out;
        }

        private static String plain(String[] lines, String cls) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) line(sb, l, cls);
            return sb.toString();
        }

        private static void line(StringBuilder sb, String text, String cls) {
            sb.append(cls.isEmpty() ? "<span>" : "<span class=" + cls + ">").append(esc(text)).append("</span>");
        }
    }
}
