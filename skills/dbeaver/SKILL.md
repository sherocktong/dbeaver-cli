---
name: dbeaver
description: Query and operate databases through the DBeaver REST API server plugin using the `dbeaver-cli` wrapper. Use when the user asks to run SQL, list/connect/disconnect DBeaver connections, or do any DB operation via a connection configured in the local DBeaver app (cp-azure-prod Databricks, cp-tc-prod Tencent DLC, etc.). Prefer this over direct database CLIs when the connection/credentials only exist inside DBeaver.
---

# DBeaver REST API skill

Operate any database connection configured in the local DBeaver app via the
`org.jkiss.dbeaver.rest.server` plugin's REST API (loopback HTTP + bearer token).

## Prerequisites

- DBeaver CE running with the REST server plugin installed and started.
  Check with: `dbeaver-cli version` — if it fails, the plugin is not up
  (deploy with `/Users/kangtong/Documents/x/dbeaver-cli/.claude/scripts/deploy-dbeaver-plugin.sh`).
- No manual token handling needed: `dbeaver-cli` reads the bearer token from
  `~/Library/DBeaverData/workspace6/.metadata/dbeaver-rest-server.properties`
  (override with env `DBEAVER_REST_TOKEN` or `DBEAVER_WORKSPACE`).

## Entrypoint: dbeaver-cli

Wrapper script: `/Users/kangtong/Documents/x/dbeaver-cli/bin/dbeaver-cli`
(add that `bin/` to PATH or call it by absolute path).

```bash
dbeaver-cli version                          # server/product info (health check)
dbeaver-cli datasources                      # list connections as a table (name, driver, status, id)
dbeaver-cli datasources --json               # raw JSON (includes url - may embed credentials, do not print)
dbeaver-cli connect <id-or-name>             # connect a data source
dbeaver-cli disconnect <id-or-name>          # disconnect a data source
dbeaver-cli sql <id-or-name> "<sql>" [rows]      # inline SQL, table output (default maxRows=1000, cap 100000)
dbeaver-cli sql <id-or-name> -f <file> [rows]    # SQL from a file
dbeaver-cli sql <id-or-name> - [rows]            # SQL from stdin
# append --json to any sql form for the raw JSON API response (needed for scripting/parsing)
```

`<id-or-name>` accepts either the connection id or its display name
(e.g. `cp-tc-prod`). `sql` auto-connects the data source if it is not connected.

## Latency and timeouts

Queries are executed synchronously by DBeaver and the CLI waits for the full
result. Cold query engines are SLOW: Tencent DLC (cp-tc-prod) Spark tasks take
2-7 minutes for the first query after idle (session/cluster cold start);
Databricks (cp-azure-prod) warehouse cold start is similar. This is normal —
the CLI prints "Executing on <ds> (timeout Ns...)" and then blocks.

- Per-request timeout: env `DBEAVER_REST_TIMEOUT` in seconds (default 600).
  On timeout the CLI exits 1 with a clear message; the query may STILL be
  running server-side in DBeaver (check the DBeaver UI before retrying
  heavy statements).
- Do NOT Ctrl-C a slow sql call and assume it failed — just wait, or rerun
  with a higher `DBEAVER_REST_TIMEOUT`. For interactive agent runs, set the
  tool/shell timeout above 600s or run the command in a background session
  and poll.
- After the first query warms the engine, subsequent queries on the same
  connection are fast (seconds).

Examples:
```bash
dbeaver-cli sql cp-tc-prod "SHOW DATABASES" 50
dbeaver-cli sql cp-azure-prod "SELECT * FROM samples.nyctaxi.trips LIMIT 5"
dbeaver-cli sql cp-tc-prod "INSERT INTO t VALUES (1)"   # DML returns updateCount
dbeaver-cli sql cp-tc-prod -f report.sql 500            # multi-line file; maxRows is arg AFTER the file
```

## DLC (cp-tc-prod) gotchas

DLC tables are Spark v2 tables; the DLC JDBC gateway does not return output
for some utility commands:
- `DESCRIBE` / `DESC` work because the DLC connector plugin
  (com.tencent.dbeaver.ext.dlc, repo ~/Documents/x/tencent-dlc-sts-connector)
  rewrites them to an `information_schema.columns` SELECT
  (`DlcQueryRewriter`). The rewrite covers plain AND prepared statements;
  if DESCRIBE ever returns a single empty `result` column again, that
  connector's prepared-statement path has regressed.
- `SHOW CREATE TABLE` succeeds server-side but returns a single empty
  `result` column (0 rows); `SHOW COLUMNS` fails with
  `[NOT_SUPPORTED_COMMAND_FOR_V2_TABLE]`.
- `SHOW DATABASES` / `SHOW TABLES [IN db]` work normally.
- Manual schema inspection alternative: `select * from db.table limit 1`
  with `--json` and read the `columns` metadata (name/typeName/dataKind).

## Response shapes (raw JSON via --json or curl)

- `GET /api/datasources` → array of `{id, name, description, driver, driverId, url, connected, project}`
- `POST /api/sql/execute` →
  - query: `{hasResultSet: true, columns: [{name, typeName, dataKind}], rows: [[...strings|null]], truncated: bool}`
  - DML/DDL: `{hasResultSet: false, updateCount: N}`
  - All cell values are display strings (or JSON null), not typed JSON values.
- Errors: `{"error": "..."}` with HTTP 4xx/5xx. 403 = bad/missing token,
  404 = unknown data source, 503-ish "platform is not started" = DBeaver still booting (retry).

## Agent guidance

- Run `dbeaver-cli datasources` first if you don't know the exact connection
  name/id; never guess connection ids.
- Read-only SQL (SELECT/SHOW/DESCRIBE) may run directly. For mutating
  statements (INSERT/UPDATE/DELETE/DROP/ALTER/CREATE), show the SQL and get
  explicit user approval first.
- Always pass a sane row limit for exploratory queries (e.g. `dbeaver-cli sql ds "..." 100`).
  Table output marks truncation with "TRUNCATED at maxRows"; with --json check the `truncated` flag
  before claiming results are complete.
- `curl` is acceptable instead of the wrapper when raw control is needed:
  base URL `http://127.0.0.1:17840/api`, header
  `Authorization: Bearer $(sed -n 's/^token=//p' ~/Library/DBeaverData/workspace6/.metadata/dbeaver-rest-server.properties)`.
  Endpoints: `GET /version`, `GET /datasources[/{id}]`,
  `POST /datasources/{id}/connect|disconnect`,
  `POST /sql/execute` with body `{"dataSource","sql","maxRows"}`.
- Security: never print full datasource `url` fields into user-facing output or
  logs — they can embed credentials/tokens. Quote name/driver/connected instead.

## Plugin repo

Source, build and deploy scripts: `/Users/kangtong/Documents/x/dbeaver-cli`
(packaging: `.claude/scripts/package-dbeaver-plugin.sh`, deploy:
`.claude/scripts/deploy-dbeaver-plugin.sh`).
