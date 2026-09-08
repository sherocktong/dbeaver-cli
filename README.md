# dbeaver-cli

A DBeaver CE plugin that starts an embedded REST API server inside the DBeaver
process, so DBeaver connections and SQL execution can be controlled from the
command line with `curl`.

Unlike the headless `dbvr` tool or CloudBeaver, the server runs **in-process**
in the DBeaver GUI and works with the same workspace: existing connections,
saved credentials, SSH tunnels and SSL settings are reused as-is.

## Layout

- `org.jkiss.dbeaver.rest.server/` - the Eclipse/OSGi plugin (standard DBeaver plugin layout)
- `bin/dbeaver-cli` - a small `curl` wrapper script for the API

## Endpoints

Base URL: `http://localhost:17840/api` (loopback only by default).
All requests need an `Authorization: Bearer <token>` header. The token is
generated on first start and stored in
`<workspace>/.metadata/dbeaver-rest-server.properties` (macOS default:
`~/Library/DBeaverData/workspace6/.metadata/dbeaver-rest-server.properties`).

| Method | Path | Description |
|--------|------|-------------|
| GET  | `/version` | DBeaver product info |
| GET  | `/datasources` | List all connections (all projects) |
| GET  | `/datasources/{idOrName}` | Connection details |
| POST | `/datasources/{idOrName}/connect` | Connect |
| POST | `/datasources/{idOrName}/disconnect` | Disconnect |
| POST | `/sql/execute` | Execute SQL, body: `{"dataSource": "...", "sql": "...", "maxRows": 1000}` |

`/sql/execute` returns either a result set:

```json
{
  "hasResultSet": true,
  "columns": [{"name": "id", "typeName": "int4", "dataKind": "NUMERIC"}],
  "rows": [["1"], ["2"]],
  "truncated": false
}
```

or an update count (`{"hasResultSet": false, "updateCount": 3}`).
Row values are display strings; SQL NULL is JSON `null`.

## Configuration

System properties (e.g. in `dbeaver.ini`):

- `dbeaver.rest.enabled=false` - disable the server
- `dbeaver.rest.port=9999` - change the listen port (default `17840`)
- `dbeaver.rest.bindAll=true` - bind to all interfaces (default: loopback only)

## Usage

```bash
# One-time: put bin/ on PATH or call it directly
export DBEAVER_WORKSPACE=~/Library/DBeaverData/workspace6   # if not default

bin/dbeaver-cli version
bin/dbeaver-cli datasources
bin/dbeaver-cli connect my-postgres
bin/dbeaver-cli sql my-postgres "select * from actor limit 5"
bin/dbeaver-cli sql my-postgres "update actor set first_name='A' where actor_id=1"
```

Plain curl works too:

```bash
TOKEN=$(sed -n 's/^token=//p' ~/Library/DBeaverData/workspace6/.metadata/dbeaver-rest-server.properties)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:17840/api/datasources
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"dataSource":"my-postgres","sql":"select 1"}' \
  http://localhost:17840/api/sql/execute
```

## Building and installing

The plugin follows the standard DBeaver plugin build. It must be built as part
of the DBeaver source tree (Tycho resolves dependencies from the DBeaver
target platform):

1. Clone the DBeaver sources and its common libraries next to each other:

   ```bash
   git clone https://github.com/dbeaver/dbeaver.git
   git clone https://github.com/dbeaver/dbeaver-common.git
   ```

2. Copy the plugin into the tree and register it:

   ```bash
   cp -r org.jkiss.dbeaver.rest.server dbeaver/plugins/
   # add <module>org.jkiss.dbeaver.rest.server</module> to dbeaver/plugins/pom.xml
   # add the plugin to dbeaver/features/org.jkiss.dbeaver.ce.feature/feature.xml:
   #   <plugin id="org.jkiss.dbeaver.rest.server" version="0.0.0"/>
   ```

3. Build:

   ```bash
   cd dbeaver
   mvn package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce,product-dbeaver-eclipse-ce
   ```

For quick IDE-based testing you can instead import the plugin into an Eclipse
workspace together with the DBeaver sources and launch DBeaver from the IDE -
the `org.eclipse.ui.startup` extension starts the server automatically.

## Notes and limitations

- The server runs in the DBeaver GUI process: DBeaver must be running for the
  API to respond. SQL is executed on the same connection context the GUI uses.
- Values are returned as display strings (`DBDDisplayFormat.NATIVE`), not
  typed JSON values. Complex types (BLOB, arrays, structs) are stringified.
- `maxRows` defaults to 1000 and is capped at 100000.
- Startup race: if you call the API while DBeaver is still loading the
  workspace you get a "platform is not started yet" error - retry.
