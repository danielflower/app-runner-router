package com.danielflower.apprunner.router.lib;

import com.danielflower.apprunner.router.lib.monitoring.AppRequestListener;
import io.muserver.MuServerBuilder;
import io.muserver.murp.ReverseProxyBuilder;
import io.muserver.rest.CORSConfig;
import io.muserver.rest.CORSConfigBuilder;

import java.io.File;
import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;

import static io.muserver.Mutils.fullPath;

public class AppRunnerRouterSettings {

    public static Builder appRunnerRouterSettings() {
        return new Builder();
    }

    private final MuServerBuilder muServerBuilder;
    private final CORSConfig corsConfig;
    private final AppRequestListener appRequestListener;
    private final HttpClient reverseProxyHttpClient;
    private final long proxyTimeoutMillis;
    private final File dataDir;
    private final boolean discardClientForwarded;
    private final String defaultAppName;
    private final RunnerUrlVerifier runnerUrlVerifier;
    private final boolean allowUntrustedInstances;

    public MuServerBuilder muServerBuilder() {
        return muServerBuilder;
    }

    public CORSConfig corsConfig() {
        return corsConfig;
    }

    public AppRequestListener appRequestListener() {
        return appRequestListener;
    }

    public HttpClient reverseProxyHttpClient() {
        return reverseProxyHttpClient;
    }

    public long proxyTimeoutMillis() {
        return proxyTimeoutMillis;
    }

    public File dataDir() {
        return dataDir;
    }

    public boolean discardClientForwarded() {
        return discardClientForwarded;
    }

    public String defaultAppName() {
        return defaultAppName;
    }

    public RunnerUrlVerifier runnerUrlVerifier() { return runnerUrlVerifier; }

    public boolean allowUntrustedInstances() {
        return allowUntrustedInstances;
    }

    private AppRunnerRouterSettings(MuServerBuilder muServerBuilder, CORSConfig corsConfig, AppRequestListener appRequestListener, HttpClient reverseProxyHttpClient, long proxyTimeoutMillis, File dataDir, boolean discardClientForwarded, String defaultAppName, RunnerUrlVerifier runnerUrlVerifier, boolean allowUntrustedInstances) {
        this.muServerBuilder = muServerBuilder;
        this.corsConfig = corsConfig;
        this.appRequestListener = appRequestListener;
        this.reverseProxyHttpClient = reverseProxyHttpClient;
        this.proxyTimeoutMillis = proxyTimeoutMillis;
        this.dataDir = dataDir;
        this.discardClientForwarded = discardClientForwarded;
        this.defaultAppName = defaultAppName;
        this.runnerUrlVerifier = runnerUrlVerifier;
        this.allowUntrustedInstances = allowUntrustedInstances;
    }

    @Override
    public String toString() {
        return "AppRunnerRouterSettings{" +
            "muServerBuilder=" + muServerBuilder +
            ", corsConfig=" + corsConfig +
            ", appRequestListener=" + appRequestListener +
            ", reverseProxyHttpClient=" + reverseProxyHttpClient +
            ", proxyTimeoutMillis=" + proxyTimeoutMillis +
            ", dataDir=" + dataDir +
            ", discardClientForwarded=" + discardClientForwarded +
            ", defaultAppName='" + defaultAppName + '\'' +
            '}';
    }

    public static class Builder {
        private MuServerBuilder muServerBuilder;
        private CORSConfig corsConfig;
        private AppRequestListener appRequestListener;
        private java.net.http.HttpClient reverseProxyHttpClient;
        private long proxyTimeoutMillis = 20 * 60000;
        private File dataDir;
        private boolean discardClientForwarded;
        private String defaultAppName;
        private RunnerUrlVerifier runnerUrlVerifier;
        private boolean allowUntrustedInstances;

        public Builder withDataDir(File dataDir) {
            this.dataDir = dataDir;
            return this;
        }

        public Builder withDiscardClientForwarded(boolean discardClientForwarded) {
            this.discardClientForwarded = discardClientForwarded;
            return this;
        }

        public Builder withDefaultAppName(String defaultAppName) {
            this.defaultAppName = defaultAppName;
            return this;
        }

        public Builder withMuServerBuilder(MuServerBuilder muServerBuilder) {
            this.muServerBuilder = muServerBuilder;
            return this;
        }

        public Builder withCorsConfig(CORSConfig corsConfig) {
            this.corsConfig = corsConfig;
            return this;
        }

        public Builder withAppRequestListener(AppRequestListener appRequestListener) {
            this.appRequestListener = appRequestListener;
            return this;
        }

        public Builder withReverseProxyHttpClient(java.net.http.HttpClient reverseProxyHttpClient) {
            this.reverseProxyHttpClient = reverseProxyHttpClient;
            return this;
        }

        public Builder withProxyTimeoutMillis(long proxyTimeoutMillis) {
            this.proxyTimeoutMillis = proxyTimeoutMillis;
            return this;
        }

        public Builder withRunnerUrlVerifier(RunnerUrlVerifier runnerUrlVerifier) {
            this.runnerUrlVerifier = runnerUrlVerifier;
            return this;
        }

        public Builder withAllowUntrustedInstances(boolean allowUntrustedInstances) {
            this.allowUntrustedInstances = allowUntrustedInstances;
            return this;
        }

        public AppRunnerRouterSettings build() {
            if (dataDir == null) {
                throw new IllegalStateException("No dataDir has been specified");
            }
            if (!dataDir.isDirectory()) {
                if (!dataDir.mkdirs()) {
                    throw new IllegalStateException("Could not create data directory at " + fullPath(dataDir));
                }
            }

            long defaultIdleTimeout = 30000;
            int defaultMaxHeadersSize = 24 * 1024;

            MuServerBuilder muServerBuilder = this.muServerBuilder != null ? this.muServerBuilder :
                MuServerBuilder.muServer()
                    .withHttpPort(0)
                    .withIdleTimeout(defaultIdleTimeout + 5000 /* allow the rp client to time out first to give better errors back to clients */, TimeUnit.MILLISECONDS)
                    .withMaxHeadersSize(defaultMaxHeadersSize)
                ;
            CORSConfig corsConfig = this.corsConfig != null ? this.corsConfig : CORSConfigBuilder.corsConfig().build();

            java.net.http.HttpClient rpHttpClient = this.reverseProxyHttpClient;
            if (rpHttpClient == null) {
                rpHttpClient = ReverseProxyBuilder.createHttpClientBuilder(allowUntrustedInstances).build();
            }

            RunnerUrlVerifier runnerUrlVerifier = this.runnerUrlVerifier != null ? this.runnerUrlVerifier : new RunnerUrlVerifier() {};

            return new AppRunnerRouterSettings(muServerBuilder, corsConfig, appRequestListener, rpHttpClient, proxyTimeoutMillis, dataDir, discardClientForwarded, defaultAppName, runnerUrlVerifier, allowUntrustedInstances);
        }
    }
}
