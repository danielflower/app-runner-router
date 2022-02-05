package com.danielflower.apprunner.router.lib.monitoring;

import org.json.JSONObject;

public class RequestInfo {
    public long startTime;
    public long endTime;
    public String appName;
    public String remoteAddr;
    public String method;
    public int responseStatus;
    public String url;
    public String targetHost;

    public String toJSON() {
        return new JSONObject()
            .put("remote", remoteAddr)
            .put("method", method)
            .put("url", url)
            .put("app", appName)
            .put("targetHost", targetHost)
            .put("start", startTime)
            .put("end", endTime)
            .put("status", responseStatus)
            .toString();
    }
}
