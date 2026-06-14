package nodebox;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Submits a DMG to Apple's notary service and staples the resulting ticket.
 *
 * <p>Uses {@code notarytool} (altool's notarization service was retired by Apple in
 * November 2023). Credentials are read from the environment so the same command runs
 * locally and in CI:
 * <ul>
 *     <li>{@code APPLE_ID} – Apple ID email</li>
 *     <li>{@code APPLE_APP_SPECIFIC_PASSWORD} – app-specific password for that Apple ID</li>
 *     <li>{@code APPLE_TEAM_ID} – Developer Team ID</li>
 * </ul>
 *
 * <p>If the submission is not {@code Accepted}, the full notary log is printed (it lists
 * exactly which file/issue caused the rejection) before failing.
 */
public class Notarizer {

    public static void main(String[] args) throws IOException, InterruptedException {
        File dmgFile = new File(args[0]);
        if (!dmgFile.exists()) {
            throw new RuntimeException("File " + args[0] + " does not exist.");
        }

        String appleId = requireEnv("APPLE_ID");
        String password = requireEnv("APPLE_APP_SPECIFIC_PASSWORD");
        String teamId = requireEnv("APPLE_TEAM_ID");

        Result submit = exec("xcrun", "notarytool", "submit", dmgFile.getAbsolutePath(),
                "--apple-id", appleId, "--password", password, "--team-id", teamId,
                "--output-format", "json", "--wait");

        String id = extract(submit.output, "\"id\"\\s*:\\s*\"([^\"]+)\"");
        String status = extract(submit.output, "\"status\"\\s*:\\s*\"([^\"]+)\"");
        System.out.println("Notarization status: " + status + " (submission " + id + ")");

        if (!"Accepted".equals(status)) {
            if (id != null) {
                System.out.println("=== notary log (rejection details) ===");
                exec("xcrun", "notarytool", "log", id,
                        "--apple-id", appleId, "--password", password, "--team-id", teamId);
            }
            throw new RuntimeException("Notarization was not accepted (status=" + status + ").");
        }

        Result staple = exec("xcrun", "stapler", "staple", dmgFile.getAbsolutePath());
        if (staple.exit != 0) {
            throw new RuntimeException("stapler staple failed (exit " + staple.exit + ").");
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new RuntimeException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private record Result(String output, int exit) {
    }

    /** Runs a command, streaming combined stdout/stderr to the console and capturing it. */
    private static Result exec(String... command) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(command));
        System.out.println("command = " + cmd);
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                builder.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        return new Result(builder.toString(), exit);
    }
}
