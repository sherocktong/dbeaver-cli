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

import org.eclipse.core.runtime.Plugin;
import org.jkiss.dbeaver.Log;
import org.osgi.framework.BundleContext;

/**
 * Plugin activator. Stops the REST server when the bundle shuts down.
 */
public class RestServerPlugin extends Plugin {
    private static final Log log = Log.getLog(RestServerPlugin.class);

    private static RestServerPlugin instance;

    public RestServerPlugin() {
        instance = this;
    }

    public static RestServerPlugin getInstance() {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        instance = this;
        super.start(context);
        log.debug("DBeaver REST API server bundle started");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        RestApiServer.shutdownInstance();
        super.stop(context);
        instance = null;
    }
}
