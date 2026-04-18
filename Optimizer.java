import soot.*;
import soot.options.Options;

public class Optimizer {
    public static void main(String[] args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: Optimizer <test1..test10|public_case>");
        }

        String classPath = ".";
        boolean isPublicCase = "public_case".equals(args[0]);
        String processDir = isPublicCase ? "./testcases/public_case" : "./testcases";
        String mainClass = isPublicCase ? "Test" : "testcases." + args[0];

        // 1. Setting global options
        Options.v().set_keep_line_number(true);

        // 2. Add your transformer to the "wjtp" pack
        PackManager.v().getPack("wjtp").add(
                new Transform("wjtp.mono", new MonomorphizationTransformer()));

        String[] sootArgs = {
            "-cp", classPath,
            "-pp", // sets the class path for Soot
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
            "-f", "c",
            "-main-class", mainClass,
            "-process-dir", processDir
        };

        soot.Main.main(sootArgs);
    }
}