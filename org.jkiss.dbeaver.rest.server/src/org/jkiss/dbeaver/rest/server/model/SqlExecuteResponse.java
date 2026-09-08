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
package org.jkiss.dbeaver.rest.server.model;

import java.util.List;

/**
 * SQL execution result. Either a result set ({@code columns} + {@code rows})
 * or an {@code updateCount}. Row values are display strings, SQL NULL is JSON null.
 */
public class SqlExecuteResponse {
    public boolean hasResultSet;
    public Long updateCount;
    public List<ColumnInfo> columns;
    public List<List<String>> rows;
    public boolean truncated;
}
