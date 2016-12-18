package com.danielflower.apprunner.router.monitoring;

public interface AppRequestListener {

    void stop();

    void onRequestComplete(RequestInfo info);
}
