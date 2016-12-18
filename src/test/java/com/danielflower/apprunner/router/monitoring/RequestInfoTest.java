package com.danielflower.apprunner.router.monitoring;

import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

public class RequestInfoTest {

    @Test
    public void serialisesToJson() {
        RequestInfo info = new RequestInfo();
        info.url = "http://localhost:8080/?blah";
        info.targetHost = "localhost:9000";
        info.appName = "name";
        info.startTime = 902349234;
        info.endTime = info.startTime + 10;
        info.method = "GET";
        info.remoteAddr = "192.0.0.1";
        info.responseStatus = 200;
        JSONAssert.assertEquals("{ url: 'http://localhost:8080/?blah', targetHost: 'localhost:9000', app: 'name', start: 902349234, end: 902349244, method: 'GET', remote: '192.0.0.1', status: 200 }"
            , info.toJSON(), JSONCompareMode.STRICT);
    }

    @Test
    public void someValuesAreOptional() {
        RequestInfo info = new RequestInfo();
        info.startTime = 902349234;
        info.endTime = info.startTime + 10;
        info.method = "GET";
        JSONAssert.assertEquals("{ start: 902349234, end: 902349244, method: 'GET', status: 0 }",
            info.toJSON(), JSONCompareMode.STRICT);
    }

}