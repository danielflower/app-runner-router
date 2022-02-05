package com.danielflower.apprunner.router.lib;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ConfigTest {

    @Test
    public void envVarStyleIsSupported() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("APPSERVER_PORT", "123");
        Config config = Config.load(env, new String[0]);
        assertThat(config.get("appserver.port"), equalTo("123"));
    }

    @Test
    public void systemPropertyStyleIsSupported() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("appserver.port", "123");
        Config config = Config.load(env, new String[0]);
        assertThat(config.get("appserver.port"), equalTo("123"));
    }
}
