package com.danielflower.apprunner.router;

import com.danielflower.apprunner.router.problems.AppRunnerException;
import com.danielflower.apprunner.router.problems.InvalidConfigException;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


public class Config {
    public static final String SERVER_HTTP_PORT = "appserver.port";
    public static final String SERVER_HTTPS_PORT = "appserver.https.port";
    public static final String DATA_DIR = "appserver.data.dir";
    public static final String DEFAULT_APP_NAME = "appserver.default.app.name";

    public static Config load(Map<String, String> systemEnv, String[] commandLineArgs) throws IOException {
        Map<String, String> env = new HashMap<>(systemEnv);
        for (Map.Entry<String, String> s : systemEnv.entrySet()) {
            env.put(s.getKey().toLowerCase().replace('_', '.'), s.getValue());
        }
        for (String key : System.getProperties().stringPropertyNames()) {
            String value = System.getProperty(key);
            env.put(key, value);
        }
        for (String commandLineArg : commandLineArgs) {
            File file = new File(commandLineArg);
            if (file.isFile()) {
                Properties props = new Properties();
                try (FileInputStream inStream = new FileInputStream(file)) {
                    props.load(inStream);
                }
                for (String key : props.stringPropertyNames()) {
                    env.put(key, props.getProperty(key));
                }
            }
        }
        return new Config(env);
    }

    private final Map<String, String> raw;

    public Config(Map<String, String> raw) {
        this.raw = raw;
    }

    public static String dirPath(File samples) {
        try {
            return samples.getCanonicalPath();
        } catch (IOException e) {
            return samples.getAbsolutePath();
        }
    }

    public String get(String name, String defaultVal) {
        return raw.getOrDefault(name, defaultVal);
    }

    public String get(String name) {
        String s = get(name, null);
        if (s == null) {
            throw new InvalidConfigException("Missing config item: " + name);
        }
        return s;
    }

    public int getInt(String name, int defaultValue) {
        String s = get(name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new InvalidConfigException("Could not convert " + name + "=" + s + " to an integer");
        }
    }

    public long getLong(String name, long defaultValue) {
        String s = get(name, String.valueOf(defaultValue));
        try {
            return Long.parseLong(s, 10);
        } catch (NumberFormatException e) {
            throw new InvalidConfigException("Could not convert " + name + "=" + s + " to an integer");
        }
    }

    public File getFile(String name) {
        File f = new File(get(name));
        if (!f.isFile()) {
            throw new InvalidConfigException("Could not find " + name + " file: " + dirPath(f));
        }
        return f;
    }

    public File getOrCreateDir(String name) {
        File f = new File(get(name));
        try {
            FileUtils.forceMkdir(f);
        } catch (IOException e) {
            throw new AppRunnerException("Could not create " + dirPath(f));
        }
        return f;
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        return Boolean.parseBoolean(get(name, String.valueOf(defaultValue)));
    }
}

