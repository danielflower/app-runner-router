package com.danielflower.apprunner.router;

import com.danielflower.apprunner.router.problems.AppRunnerException;
import com.danielflower.apprunner.router.problems.InvalidConfigException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static com.danielflower.apprunner.router.FileSandbox.dirPath;


public class Config {
    public static final String SERVER_PORT = "appserver.port";
    public static final String DATA_DIR = "appserver.data.dir";
    public static final String DEFAULT_APP_NAME = "appserver.default.app.name";
    public static final String INITIAL_APP_URL = "appserver.initial.app.url";

    public static final String JAVA_HOME = "JAVA_HOME";
    public static final String LEIN_JAR = "LEIN_JAR";
    public static final String LEIN_JAVA_CMD = "LEIN_JAVA_CMD";

    public static Config load(String[] commandLineArgs) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
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


    private static String windowsinize(String command) {
        return SystemUtils.IS_OS_WINDOWS ? command + ".exe" : command;
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

    public int getInt(String name) {
        String s = get(name);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new InvalidConfigException("Could not convert " + name + "=" + s + " to an integer");
        }
    }

    public File getDir(String name) {
        File f = new File(get(name));
        if (!f.isDirectory()) {
            throw new AppRunnerException("Could not find " + name + " directory: " + dirPath(f));
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

    public File getFile(String name) {
        File f = new File(get(name));
        if (!f.isFile()) {
            throw new AppRunnerException("Could not find " + name + " file: " + dirPath(f));
        }
        return f;
    }
}

