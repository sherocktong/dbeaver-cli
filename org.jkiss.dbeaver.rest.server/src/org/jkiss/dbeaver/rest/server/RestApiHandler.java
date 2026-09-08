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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.rest.server.controller.RestApiController;
import org.jkiss.dbeaver.rest.server.model.RestError;
import org.jkiss.dbeaver.rest.server.model.RestNotFoundException;
import org.jkiss.dbeaver.rest.server.model.SqlExecuteRequest;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.HttpConstants;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Root HTTP handler. Authenticates requests with a bearer token and
 * routes them to the API controller.
 */
public class RestApiHandler implements HttpHandler {
    private static final Log log = Log.getLog(RestApiHandler.class);

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    @NotNull
    private final RestApiController controller;
    @NotNull
    private final String authToken;

    public RestApiHandler(@NotNull RestApiController controller, @NotNull String authToken) {
        this.controller = controller;
        this.authToken = authToken;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            InetSocketAddress remoteAddress = exchange.getRemoteAddress();
            if (remoteAddress == null || !remoteAddress.getAddress().isLoopbackAddress()) {
                sendError(exchange, 403, "Remote address is not allowed");
                return;
            }
            String authHeader = exchange.getRequestHeaders().getFirst(HttpConstants.HEADER_AUTHORIZATION);
            if (CommonUtils.isEmpty(authHeader)
                || !authHeader.startsWith(HttpConstants.BEARER_PREFIX)
                || !authToken.equals(authHeader.substring(HttpConstants.BEARER_PREFIX.length()))
            ) {
                sendError(exchange, 403, "Not authorized");
                return;
            }

            route(exchange);
        } catch (RestNotFoundException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (DBException e) {
            log.debug("REST API error", e);
            sendError(exchange, 500, e.getMessage());
        } catch (Throwable e) {
            log.error("Unexpected REST API error", e);
            sendError(exchange, 500, "Internal error: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void route(@NotNull HttpExchange exchange) throws DBException, IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        // Strip the "/api" context prefix
        if (path.startsWith("/api")) {
            path = path.substring("/api".length());
        }
        String[] segments = CommonUtils.isEmpty(path) ? new String[0] : path.substring(1).split("/");

        Object result;
        if (segments.length == 0) {
            result = controller.getServerInfo();
        } else if (segments.length == 1 && "version".equals(segments[0]) && "GET".equals(method)) {
            result = controller.getServerInfo();
        } else if (segments.length == 1 && "datasources".equals(segments[0]) && "GET".equals(method)) {
            result = controller.getDataSources();
        } else if (segments.length == 2 && "datasources".equals(segments[0])) {
            if ("GET".equals(method)) {
                result = controller.getDataSource(segments[1]);
            } else {
                throw new RestNotFoundException("Unknown endpoint: " + method + " " + path);
            }
        } else if (segments.length == 3 && "datasources".equals(segments[0]) && "POST".equals(method)) {
            if ("connect".equals(segments[2])) {
                result = controller.connect(segments[1]);
            } else if ("disconnect".equals(segments[2])) {
                result = controller.disconnect(segments[1]);
            } else {
                throw new RestNotFoundException("Unknown endpoint: " + method + " " + path);
            }
        } else if (segments.length == 2 && "sql".equals(segments[0]) && "execute".equals(segments[1]) && "POST".equals(method)) {
            SqlExecuteRequest request = GSON.fromJson(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8),
                SqlExecuteRequest.class);
            result = controller.executeSql(request);
        } else {
            throw new RestNotFoundException("Unknown endpoint: " + method + " " + path);
        }

        sendJson(exchange, 200, result);
    }

    private static void sendJson(@NotNull HttpExchange exchange, int status, @NotNull Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(@NotNull HttpExchange exchange, int status, String message) {
        try {
            sendJson(exchange, status, new RestError(message));
        } catch (IOException e) {
            log.debug("Error sending REST error response", e);
        }
    }

}
