package com.danielflower.apprunner.router.mgmt;

import java.util.List;

public interface MapManager {
    void loadAll(List<Runner> runners) throws InterruptedException;

    void loadRunner(Runner runner) throws Exception;

    void removeRunner(Runner runner);
}
