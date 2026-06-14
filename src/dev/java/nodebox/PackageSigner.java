// Inspired by https://github.com/dg76/signpackage
package nodebox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackageSigner {
    // Overridable so CI can pass the identity that was imported into the runner's keychain.
    private static final String IDENTITY = System.getenv().getOrDefault(
            "MACOS_SIGN_IDENTITY", "Developer ID Application: Frederik De Bleser (5X78EYG9RH)");

    public static void main(String[] args) throws IOException {
        File projectDir = new File(".");
        // Sign native libraries packed inside the fat jar BEFORE sealing the app: the notary
        // service inspects Mach-O code inside jars, and rewriting the jar afterwards would
        // invalidate the app bundle's seal.
        signNativeLibsInJar(new File("dist/mac/NodeBox.app/Contents/app/lib/nodebox.jar"), projectDir);
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

    /**
     * Signs the macOS native libraries (.jnilib/.dylib) bundled inside a jar (e.g. JNA's
     * libjnidispatch, jffi, jansi). Each is extracted, codesigned, and written back into the
     * jar. Apple's notary service rejects unsigned Mach-O code even when it lives inside a jar.
     */
    private static void signNativeLibsInJar(File jar, File projectDir) throws IOException {
        if (!jar.exists()) {
            return;
        }
        List<String> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.endsWith(".jnilib") || name.endsWith(".dylib")) {
                    entries.add(name);
                }
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        Path tmp = Files.createTempDirectory("nbjarsign");
        for (String entry : entries) {
            runProcess(null, "unzip", "-o", "-q", jar.getAbsolutePath(), entry, "-d", tmp.toString());
            File extracted = new File(tmp.toFile(), entry);
            if (!isMachO(extracted)) {
                continue; // skip non-macOS slices (e.g. linux .so) the notary doesn't care about
            }
            System.out.println("jar lib: " + entry);
            signFile(extracted, projectDir);
            // Replace the entry in the jar with the signed copy; run from tmp so the stored path matches.
            runProcess(tmp.toFile(), "zip", "-q", jar.getAbsolutePath(), entry);
        }
    }

    private static boolean isMachO(File f) throws IOException {
        if (!f.isFile()) {
            return false;
        }
        byte[] magic = new byte[4];
        try (var in = Files.newInputStream(f.toPath())) {
            if (in.read(magic) != 4) {
                return false;
            }
        }
        int m = ((magic[0] & 0xff) << 24) | ((magic[1] & 0xff) << 16) | ((magic[2] & 0xff) << 8) | (magic[3] & 0xff);
        // thin Mach-O (32/64, both byte orders) and fat/universal binaries
        return m == 0xFEEDFACE || m == 0xCEFAEDFE || m == 0xFEEDFACF || m == 0xCFFAEDFE
                || m == 0xCAFEBABE || m == 0xBEBAFECA;
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

    private static void runProcess(File dir, String... command) {
        try {
            int code = new ProcessBuilder(command).directory(dir).inheritIO().start().waitFor();
            if (code != 0) {
                throw new RuntimeException(command[0] + " failed (exit " + code + ")");
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("Failed to run " + command[0], e);
        }
    }
}
