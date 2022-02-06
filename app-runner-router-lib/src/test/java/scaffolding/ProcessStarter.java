package scaffolding;

import org.apache.commons.exec.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static io.muserver.Mutils.fullPath;

public class ProcessStarter {
    public final Logger log;

    public ProcessStarter(Logger log) {
        this.log = log;
    }

    public Killer startDaemon(Map<String, String> envVarsForApp, CommandLine command, File workingDir, Waiter startupWaiter) {
        long startTime = logStartInfo(command, workingDir);
        Killer watchDog = new Killer(ExecuteWatchdog.INFINITE_TIMEOUT);
        Executor executor = createExecutor(workingDir, watchDog);

        try {
            DefaultExecuteResultHandler handler = new DefaultExecuteResultHandler();
            executor.execute(command, envVarsForApp, handler);

            startupWaiter.or(c -> handler.hasResult()); // stop waiting if the process exist
            startupWaiter.blockUntilReady();

            if (handler.hasResult()) {
                String message = "The project at " + fullPath(workingDir) + " started but exited all too soon. Check the console log for information.";
                throw new RuntimeException(message);
            }
        } catch (TimeoutException te) {
            String message = "Built successfully, but timed out waiting for startup at " + fullPath(workingDir);
            watchDog.destroyProcess();
            throw new RuntimeException(message);
        } catch (RuntimeException pcse) {
            throw pcse;
        } catch (Exception e) {
            String message = "Built successfully, but error on start for " + fullPath(workingDir);
            throw new RuntimeException(message, e);
        }

        logEndTime(command, startTime);
        return watchDog;
    }


    public long logStartInfo(CommandLine command, File projectRoot) {
        log.info("Starting " + fullPath(projectRoot) + "> " + StringUtils.join(command.toStrings(), " "));
        return System.currentTimeMillis();
    }

    private void logEndTime(CommandLine command, long startTime) {
        log.info("Completed " + command.getExecutable() + " in " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private Executor createExecutor(File workingDir, ExecuteWatchdog watchDog) {
        Executor executor = new DefaultExecutor();
        executor.setWorkingDirectory(workingDir);
        executor.setWatchdog(watchDog);
        executor.setStreamHandler(new PumpStreamHandler(new LogOutputStream() {
            @Override
            protected void processLine(String line, int logLevel) {
                log.info(line);
            }
        }));
        return executor;
    }

}
