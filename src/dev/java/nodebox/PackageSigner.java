// Inspired by https://github.com/dg76/signpackage
package nodebox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

public class PackageSigner {
    // Overridable so CI can pass the identity that was imported into the runner's keychain.
    private static final String IDENTITY = System.getenv().getOrDefault(
            "MACOS_SIGN_IDENTITY", "Developer ID Application: Frederik De Bleser (5X78EYG9RH)");

    public static void main(String[] args) {
        File projectDir = new File(".");
        scanRecursive(new File("dist/mac"), projectDir);
        signFile(new File("dist/mac/NodeBox.app"), projectDir);
    }

    public static void scanRecursive(File dir, File projectDir) {
        for (File f : Objects.requireNonNull(dir.listFiles())) {
            if (f.isDirectory()) {
                scanRecursive(f, projectDir);
            } else if (f.getName().endsWith(".dylib") || f.canExecute()) {
                System.out.println(f.getAbsolutePath());
                signFile(f, projectDir);
            }
        }
    }

    private static void signFile(File f, File projectDir) {

        ArrayList<String> command = new ArrayList<>();
        command.add("codesign");
        command.add("--sign");
        command.add(IDENTITY);
        command.add("--timestamp");
        // No --deep: nested code is signed individually (inside-out) by scanRecursive.
        // --deep would re-sign that nested code without a per-binary secure timestamp,
        // which Apple's notary service rejects.
        command.add("-vvvv");
        command.add("-f");
        command.add("--entitlements");
        command.add(new File(projectDir, "platform/mac/NodeBox.entitlements").getAbsolutePath());
        command.add("--options");
        command.add("runtime");
        command.add(f.getAbsolutePath());
        System.out.println("command = " + command);
        try {
            int exitCode = new ProcessBuilder().directory(f.getParentFile()).inheritIO().command(command).start().waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("codesign failed (exit " + exitCode + ") for " + f.getAbsolutePath());
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("Failed to run codesign for " + f.getAbsolutePath(), e);
        }
    }
}
