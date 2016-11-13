package com.danielflower.apprunner.router.mgmt;

import org.json.JSONObject;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.net.URI;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

public class RunnerTest {
    private Runner runner = new Runner("one", URI.create("http://localhost:8232"), 3);

    @Test
    public void toMapMapsTheValues() throws Exception {
        JSONObject expected = new JSONObject();
        expected.put("id", "one");
        expected.put("url", "http://localhost:8232");
        expected.put("maxApps", 3);
        expected.put("systemUrl", "http://localhost:8232/api/v1/system");
        JSONAssert.assertEquals(expected, runner.toJSON(), JSONCompareMode.LENIENT);
    }

    @Test
    public void runnersCanBeRoundTripped() {
        assertThat(Runner.fromJSON(runner.toJSON()), equalTo(runner));
    }
}
