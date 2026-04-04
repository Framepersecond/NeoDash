package dash.process;

import java.io.IOException;
import java.io.InputStream;

public interface ServerProcessRunner {

    void start() throws IOException;

    void stop() throws IOException;

    void kill() throws IOException;

    void sendCommand(String command) throws IOException;

    InputStream getLogStream() throws IOException;
}

