package dash.process;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

public class DockerRunner implements ServerProcessRunner {

    private final DockerClient client;
    private final String containerId;

    public DockerRunner(String containerId) {
        this.containerId = containerId;
        this.client = DockerClientBuilder.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder().build())
                .build();
    }

    @Override
    public void start() {
        client.startContainerCmd(containerId).exec();
    }

    @Override
    public void stop() {
        client.stopContainerCmd(containerId).withTimeout(20).exec();
    }

    @Override
    public void kill() {
        client.killContainerCmd(containerId).exec();
    }

    @Override
    public void sendCommand(String command) {
        String safeCommand = command == null ? "" : command.replace("\"", "\\\"");
        ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
                .withAttachStdout(false)
                .withAttachStderr(false)
                .withCmd("sh", "-lc", "printf \"%s\\n\" \"" + safeCommand + "\"")
                .exec();
        try {
            client.execStartCmd(exec.getId()).exec(new com.github.dockerjava.core.command.ExecStartResultCallback())
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public InputStream getLogStream() throws IOException {
        PipedInputStream in = new PipedInputStream(16 * 1024);
        PipedOutputStream out = new PipedOutputStream(in);

        Thread t = new Thread(() -> {
            try {
                client.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withFollowStream(true)
                        .exec(new com.github.dockerjava.core.command.LogContainerResultCallback() {
                            @Override
                            public void onNext(Frame frame) {
                                try {
                                    out.write(frame.getPayload());
                                    out.flush();
                                } catch (IOException ignored) {
                                }
                                super.onNext(frame);
                            }
                        }).awaitCompletion();
            } catch (Exception ignored) {
            } finally {
                try {
                    out.write("\n[NeoDash] Docker log stream closed.\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }, "docker-log-stream-" + containerId);
        t.setDaemon(true);
        t.start();

        return in;
    }
}

