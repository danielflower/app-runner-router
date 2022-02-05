package com.danielflower.apprunner.router.lib.monitoring;

public interface AppRequestListener {

    void stop();

    void onRequestComplete(RequestInfo info);
}
