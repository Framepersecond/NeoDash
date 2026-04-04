package dash.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ScreenRunner implements ServerProcessRunner {

    private final String serverId;
    private final String startScript;
    private final Path workingDirectory;

    public ScreenRunner(String serverId, String startScript, Path workingDirectory) {
        this.serverId = serverId;
        this.startScript = startScript;
        this.workingDirectory = workingDirectory;
    }

    @Override
    public void start() throws IOException {
        run("screen", "-dmS", serverId, "bash", "-lc", startScript);
    }

    @Override
    public void stop() throws IOException {
        sendCommand("stop");
    }

    @Override
    public void kill() throws IOException {
        run("screen", "-S", serverId, "-X", "quit");
    }

    @Override
    public void sendCommand(String command) throws IOException {
        String cmd = command == null ? "" : command;
        run("screen", "-S", serverId, "-X", "stuff", cmd + "\r");
    }

    @Override
    public InputStream getLogStream() throws IOException {
        Path latestLog = workingDirectory.resolve("logs").resolve("latest.log");
        if (!Files.exists(latestLog)) {
            Path parent = latestLog.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.createFile(latestLog);
        }
        Process tailProcess = new ProcessBuilder("tail", "-n", "100", "-F", latestLog.toString())
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        return tailProcess.getInputStream();
    }

    private void run(String... command) throws IOException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("Command failed with exit code " + exit + ": " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted: " + String.join(" ", command), e);
        }
    }
}


