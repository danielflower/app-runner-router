package com.danielflower.apprunner.router.mgmt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.util.regex.Pattern;

public class SystemInfo {
    private static final Logger log = LoggerFactory.getLogger(SystemInfo.class);

    public final String hostName;
    public final String user;
    public final Long pid;
    public final String osName;
    public final int numCpus;
    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

    public SystemInfo(String hostName, String user, Long pid, String osName, int numCpus) {
        this.hostName = hostName;
        this.user = user;
        this.pid = pid;
        this.osName = osName;
        this.numCpus = numCpus;
    }

    public static SystemInfo create() {
        Runtime runtime = Runtime.getRuntime();
        String name = ManagementFactory.getRuntimeMXBean().getName();
        Long pid = Pattern.matches("[0-9]+@.*", name) ? Long.parseLong(name.substring(0, name.indexOf('@'))) : null;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("Could not find host name so it will not be exposed on the System REST API", e);
            host = null;
        }

        return new SystemInfo(host, System.getProperty("user.name"), pid, System.getProperty("os.name"), runtime.availableProcessors());
    }

    public long uptimeInMillis() {
        return runtimeMXBean.getUptime();
    }

    @Override
    public String toString() {
        return "SystemInfo{" +
            "hostName='" + hostName + '\'' +
            ", user='" + user + '\'' +
            ", pid=" + pid +
            ", osName='" + osName + '\'' +
            ", numCpus=" + numCpus +
            '}';
    }
}
