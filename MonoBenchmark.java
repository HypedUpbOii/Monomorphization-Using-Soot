import soot.PackManager;
import soot.Transform;

/**
 * Runs the MonomorphizationTransformer over a TamiFlex-generated DaCapo output
 * directory and emits rewritten class files to a separate output directory.
 *
 * Usage:
 *   java -Xmx6g -cp ".:soot-4.6.0-jar-with-dependencies.jar" \
 *        MonoBenchmark <tami_out_dir> <mono_out_dir>
 *
 * <tami_out_dir>  : one of tami-outs/out-{luindex,avrora,...}
 * <mono_out_dir>  : where Soot writes the rewritten .class files
 */
public class MonoBenchmark {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: MonoBenchmark <tami_out_dir> <mono_out_dir>");
            System.exit(1);
        }

        String outDir  = args[0];
        String monoDir = args[1];
        String dacapoJar = "dacapo-9.12-MR1-bach.jar";

        // Use the OS-appropriate classpath separator so this works on Windows and Unix.
        String sep = java.io.File.pathSeparator;
        String cp  = outDir + sep + dacapoJar;
        String reflLog = "reflection-log:" + outDir + "/refl.log";

        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.mono", new MonomorphizationTransformer()));

        String[] sootArgs = {
            "-whole-program",
            "-allow-phantom-refs",
            "-no-bodies-for-excluded",
            "-soot-classpath",  cp,
            "-prepend-classpath",
            "-keep-line-number",
            "-main-class",     "Harness",
            "-process-dir",    outDir,
            "-p", "cg.spark",  "on",
            "-p", "cg",        reflLog,
            // Emit class files to monoDir; Soot only writes application classes.
            "-f", "c",
            "-d", monoDir,
            // Suppress errors for phantom / unresolvable refs in library code.
            "-ire",
            // Exclude JDK packages from application class set so they are not
            // re-emitted and do not interfere with the JVM's own boot classes.
            "-x", "java.",
            "-x", "javax.",
            "-x", "sun.",
            "-x", "jdk.",
            "-x", "com.sun.",
        };

        soot.Main.main(sootArgs);
    }
}
