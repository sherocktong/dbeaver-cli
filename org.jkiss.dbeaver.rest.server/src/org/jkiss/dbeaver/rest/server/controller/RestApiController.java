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
package org.jkiss.dbeaver.rest.server.controller;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.rest.server.model.ColumnInfo;
import org.jkiss.dbeaver.rest.server.model.DataSourceInfo;
import org.jkiss.dbeaver.rest.server.model.RestNotFoundException;
import org.jkiss.dbeaver.rest.server.model.ServerInfo;
import org.jkiss.dbeaver.rest.server.model.SqlExecuteRequest;
import org.jkiss.dbeaver.rest.server.model.SqlExecuteResponse;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the REST API endpoints on top of the DBeaver model.
 */
public class RestApiController {

    public static final int DEFAULT_MAX_ROWS = 1000;
    public static final int MAX_ROWS_LIMIT = 100000;

    @NotNull
    public ServerInfo getServerInfo() {
        ServerInfo info = new ServerInfo();
        info.product = GeneralUtils.getProductTitle();
        info.version = GeneralUtils.getProductVersion().toString();
        return info;
    }

    @NotNull
    public List<DataSourceInfo> getDataSources() throws DBException {
        List<DataSourceInfo> result = new ArrayList<>();
        for (DBPProject project : getProjects()) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                result.add(toDataSourceInfo(project, container));
            }
        }
        return result;
    }

    @NotNull
    public DataSourceInfo getDataSource(@NotNull String idOrName) throws DBException {
        return toDataSourceInfo(null, findDataSource(idOrName));
    }

    @NotNull
    public DataSourceInfo connect(@NotNull String idOrName) throws DBException {
        DBPDataSourceContainer container = findDataSource(idOrName);
        if (!container.isConnected()) {
            container.connect(new VoidProgressMonitor(), true, true);
        }
        return toDataSourceInfo(null, container);
    }

    @NotNull
    public DataSourceInfo disconnect(@NotNull String idOrName) throws DBException {
        DBPDataSourceContainer container = findDataSource(idOrName);
        if (container.isConnected()) {
            container.disconnect(new VoidProgressMonitor());
        }
        return toDataSourceInfo(null, container);
    }

    @NotNull
    public SqlExecuteResponse executeSql(@Nullable SqlExecuteRequest request) throws DBException {
        if (request == null || CommonUtils.isEmpty(request.dataSource)) {
            throw new DBException("'dataSource' parameter is required");
        }
        if (CommonUtils.isEmpty(request.sql)) {
            throw new DBException("'sql' parameter is required");
        }
        DBPDataSourceContainer container = findDataSource(request.dataSource);

        DBRProgressMonitor monitor = new VoidProgressMonitor();
        if (!container.isConnected()) {
            container.connect(monitor, true, true);
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            throw new DBException("Data source '" + request.dataSource + "' is not initialized");
        }

        int maxRows = request.maxRows != null && request.maxRows > 0
            ? Math.min(request.maxRows, MAX_ROWS_LIMIT)
            : DEFAULT_MAX_ROWS;

        SqlExecuteResponse response = new SqlExecuteResponse();
        try (DBCSession session = DBUtils.openUtilSession(monitor, dataSource, "Execute SQL from REST API")) {
            try (DBCStatement statement = DBUtils.makeStatement(
                null, session, DBCStatementType.QUERY, request.sql, 0, maxRows
            )) {
                boolean hasResultSet = statement.executeStatement();
                response.hasResultSet = hasResultSet;
                if (hasResultSet) {
                    readResultSet(session, statement, maxRows, response);
                } else {
                    response.updateCount = statement.getUpdateRowCount();
                }
            }
        }
        return response;
    }

    private static void readResultSet(
        @NotNull DBCSession session,
        @NotNull DBCStatement statement,
        int maxRows,
        @NotNull SqlExecuteResponse response
    ) throws DBException {
        response.columns = new ArrayList<>();
        response.rows = new ArrayList<>();
        try (DBCResultSet resultSet = statement.openResultSet()) {
            if (resultSet == null) {
                response.hasResultSet = false;
                response.updateCount = statement.getUpdateRowCount();
                return;
            }
            List<? extends DBCAttributeMetaData> attributes = resultSet.getMeta().getAttributes();
            for (DBCAttributeMetaData attribute : attributes) {
                ColumnInfo column = new ColumnInfo();
                column.name = attribute.getName();
                column.typeName = attribute.getTypeName();
                column.dataKind = attribute.getDataKind().name();
                response.columns.add(column);
            }

            DBDValueHandler valueHandler = session.getDefaultValueHandler();
            while (resultSet.nextRow()) {
                if (response.rows.size() >= maxRows) {
                    response.truncated = true;
                    break;
                }
                List<String> row = new ArrayList<>(attributes.size());
                for (int i = 0; i < attributes.size(); i++) {
                    Object value = resultSet.getAttributeValue(i);
                    row.add(value == null
                        ? null
                        : valueHandler.getValueDisplayString(attributes.get(i), value, DBDDisplayFormat.NATIVE));
                }
                response.rows.add(row);
            }
        }
    }

    @NotNull
    private static List<? extends DBPProject> getProjects() throws DBException {
        if (!DBWorkbench.isPlatformStarted() || DBWorkbench.getPlatform().getWorkspace() == null) {
            throw new DBException("DBeaver platform is not started yet. Try again later.");
        }
        return DBWorkbench.getPlatform().getWorkspace().getProjects();
    }

    @NotNull
    private static DBPDataSourceContainer findDataSource(@NotNull String idOrName) throws DBException {
        for (DBPProject project : getProjects()) {
            DBPDataSourceContainer container = project.getDataSourceRegistry().getDataSource(idOrName);
            if (container == null) {
                container = project.getDataSourceRegistry().findDataSourceByName(idOrName);
            }
            if (container != null) {
                return container;
            }
        }
        throw new RestNotFoundException("Data source not found: " + idOrName);
    }

    @NotNull
    private static DataSourceInfo toDataSourceInfo(@Nullable DBPProject project, @NotNull DBPDataSourceContainer container) {
        DataSourceInfo info = new DataSourceInfo();
        info.id = container.getId();
        info.name = container.getName();
        info.description = container.getDescription();
        info.driver = container.getDriver() == null ? null : container.getDriver().getName();
        info.driverId = container.getDriver() == null ? null : container.getDriver().getId();
        info.url = container.getConnectionConfiguration() == null ? null : container.getConnectionConfiguration().getUrl();
        info.connected = container.isConnected();
        info.project = project != null ? project.getName() : null;
        return info;
    }
}
