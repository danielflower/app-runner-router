package com.danielflower.apprunner.router.mgmt;

import org.apache.maven.shared.invoker.InvocationOutputHandler;

import java.util.ArrayList;

public interface AppDescription {
    String name();

    String gitUrl();

    String latestBuildLog();

    String latestConsoleLog();

    ArrayList<String> contributors();

    void stopApp() throws Exception;

    void update(InvocationOutputHandler outputHandler) throws Exception;
}
