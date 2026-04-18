import soot.*;
import soot.options.Options;

public class Optimizer {
    public static void main(String[] args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: Optimizer <testcase|public_case>");
        }

        String classPath = ".";
        boolean isPublicCase = "public_case".equals(args[0]);
        String processDir = isPublicCase ? "./testcases/public_case" : "./testcases/" + args[0];
        String mainClass = isPublicCase ? "Test" : "testcases." + args[0] + ".Test";

        // 1. Setting global options
        Options.v().set_keep_line_number(true);

        // 2. Add your transformer to the "wjtp" pack
        PackManager.v().getPack("wjtp").add(
                new Transform("wjtp.mono", new MonomorphizationTransformer()));

        // 3. Prepare arguments
        //
        // We deliberately omit -pp (prepend JVM classpath) because Soot 4.6.0 bundles
        // an ASM version that cannot parse class files produced by Java 17+.  Instead
        // we restrict the Soot classpath to the test directory only and allow all
        // java.* / javax.* / jdk.* references to become phantom classes.
        String[] sootArgs = {
            "-soot-classpath", processDir,
            "-w",
            "-app",
            "-p", "cg.spark", "enabled:true",
            "-p", "cg.spark", "on-fly-cg:true",
            "-allow-phantom-refs",
            "-no-bodies-for-excluded",
            "-exclude", "java.*",
            "-exclude", "javax.*",
            "-exclude", "sun.*",
            "-exclude", "com.sun.*",
            "-exclude", "jdk.*",
            "-f", "J",
            "-main-class", mainClass,
            "-process-dir", processDir
        };

        // 4. Just call main. It will parse args, load classes, and run the packs.
        soot.Main.main(sootArgs);
    }
}