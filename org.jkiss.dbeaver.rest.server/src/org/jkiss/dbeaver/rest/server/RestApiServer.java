/*
 * DBeaver REST API Server plugin
 * Copyright (C) 2026 DBeaver REST Server contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.rest.server;

import com.sun.net.httpserver.HttpServer;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.rest.server.controller.RestApiController;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.SecurityUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server exposing DBeaver data sources and SQL execution
 * over a REST API. Binds to the loopback interface and requires a bearer
 * token which is generated on first start and stored in the DBeaver
 * metadata folder.
 *
 * <p>Configuration (system properties):</p>
 * <ul>
 *     <li>{@code dbeaver.rest.enabled} - enables/disables the server (default: true)</li>
 *     <li>{@code dbeaver.rest.port} - listen port (default: {@value #DEFAULT_PORT})</li>
 *     <li>{@code dbeaver.rest.bindAll} - bind to all interfaces instead of loopback (default: false)</li>
 * </ul>
 */
public class RestApiServer {
    private static final Log log = Log.getLog(RestApiServer.class);

    public static final String PROP_ENABLED = "dbeaver.rest.enabled";
    public static final String PROP_PORT = "dbeaver.rest.port";
    public static final String PROP_BIND_ALL = "dbeaver.rest.bindAll";
    public static final int DEFAULT_PORT = 17840;
    public static final String CONFIG_FILE_NAME = "dbeaver-rest-server.properties";

    private static final String PROP_TOKEN = "token";

    private static volatile RestApiServer instance;

    private HttpServer server;
    private ExecutorService executor;
    private int port;
    private String authToken;

    private RestApiServer() {
    }

    @NotNull
    public static synchronized RestApiServer getInstance() {
        if (instance == null) {
            instance = new RestApiServer();
        }
        return instance;
    }

    public static synchronized void shutdownInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    public int getPort() {
        return port;
    }

    @Nullable
    public String getAuthToken() {
        return authToken;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public void start() {
        Thread starter = new Thread(this::startServer, "DBeaver REST API server starter");
        starter.setDaemon(true);
        starter.start();
    }

    private synchronized void startServer() {
        if (server != null) {
            return;
        }
        if (!getBooleanProperty(PROP_ENABLED, true)) {
            log.info("DBeaver REST API server is disabled (" + PROP_ENABLED + "=false)");
            return;
        }
        try {
            port = getIntProperty(PROP_PORT, DEFAULT_PORT);
            authToken = loadOrCreateAuthToken();

            InetAddress bindAddress = getBooleanProperty(PROP_BIND_ALL, false)
                ? null
                : InetAddress.getLoopbackAddress();
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 64);
            server.createContext("/api", new RestApiHandler(new RestApiController(), authToken));
            executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "DBeaver REST API worker");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();

            log.info("DBeaver REST API server started at " + getServerUrl()
                + " (bearer token is stored in " + getConfigPath().toAbsolutePath() + ")");
        } catch (Exception e) {
            log.error("Cannot start DBeaver REST API server", e);
            stop();
        }
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(1);
            } catch (Exception e) {
                log.error("Error stopping DBeaver REST API server", e);
            }
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @NotNull
    public String getServerUrl() {
        return "http://localhost:" + port + "/api";
    }

    @NotNull
    private static Path getConfigPath() {
        return GeneralUtils.getMetadataFolder().resolve(CONFIG_FILE_NAME);
    }

    @NotNull
    private static String loadOrCreateAuthToken() throws IOException {
        Path configPath = getConfigPath();
        Properties props = new Properties();
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                props.load(reader);
            }
            String token = props.getProperty(PROP_TOKEN);
            if (!CommonUtils.isEmpty(token)) {
                return token;
            }
        }

        String token = SecurityUtils.generatePassword(32);
        props.setProperty(PROP_TOKEN, token);
        Files.createDirectories(configPath.getParent());
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            props.store(writer, "DBeaver REST API server configuration");
        }
        try {
            Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system - ignore
        }
        return token;
    }

    private static boolean getBooleanProperty(@NotNull String name, boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int getIntProperty(@NotNull String name, int defaultValue) {
        String value = System.getProperty(name);
        if (CommonUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value of system property " + name + ": " + value);
            return defaultValue;
        }
    }
}
