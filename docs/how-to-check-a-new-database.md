# How to Check a New Database

This guide describes the **current** collector API. The proposed executor/trace evolution is documented separately in [Executor and Logger](plans/04-executor-and-logger.md).

## 1. Add the JDBC driver

Add the database driver to `pom.xml`, using a version compatible with JDK 11 and the target database. For example, the existing MySQL driver uses `mysql:mysql-connector-java:8.0.33`.

Load the driver explicitly in the collector when driver auto-discovery is insufficient:

```java
static {
    try {
        Class.forName("com.mysql.cj.jdbc.Driver");
    } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
    }
}
```

## 2. Implement a collector

Create a package under `src/main/java/collector/` and extend `Collector`. Provide a unique registration name and a `Properties` constructor:

```java
public class ExampleCollector extends Collector<Long, Long> {
    public static final String NAME = "EXAMPLE";

    public ExampleCollector(Properties config) {
        super(config);
    }
}
```

`Main` discovers subclasses through reflection and selects one using `db.type=EXAMPLE`. Existing names are `MYSQL`, `POSTGRES`, `POSTGRES-SAVE`, `H2`, and `MARIA`; do not reuse them.

Implement the abstract methods:

- `collect`: prepare the schema, execute sessions, fill observed read values, handle unsuccessful transactions, and return the collected history.
- `createTable`: create the required database objects.
- `createVariables`: insert initial keys and values.
- `dropDatabase`: clean up only objects owned by the test run.

Use `src/main/java/collector/mysql/MySQLCollector.java` as the current reference. Note that existing collectors use the fixed `dbtest` schema and may drop it. Never run them against a database containing user data.

## 3. Extend DBClient only when necessary

`DBClient` already provides JDBC connections, transaction isolation, commit/rollback retry, and read-write register SQL. Reuse it when the database is compatible.

Override or extend it when the dialect or operation model differs. A write currently maps to:

```sql
UPDATE dbtest.variables SET val = ? WHERE var = ?;
```

A read currently maps to:

```sql
SELECT * FROM dbtest.variables WHERE var = ?;
```

The returned value is written back into the history operation. Preserve session order, transaction boundaries, rollback behavior, and actual observed values. Do not fabricate values for failed or empty reads.

## 4. Check configuration and consumers

- Confirm `db.isolation` maps to the intended JDBC isolation constant and verify the database's actual semantics.
- Keep JDBC URLs and credentials in local configuration, not in committed examples or generated artifacts.
- If the UI needs a new database option, update `frontend/src/components/Configuration.vue` as well as the Java collector.
- Add a Compose file under `scripts/standalone/` only when it provides a reproducible local test service.

## 5. Validate

Using JDK 11 and a dedicated database:

1. Run `mvn -B -DskipTests package` and the relevant local tests.
2. Execute a small generated workload through the CLI; verify keys, read values, transaction outcomes, and cleanup.
3. Exercise retry and rollback behavior with a controlled failure.
4. Run the same small case through FastAPI and inspect logs, bug output, and result packaging.
5. Document any unverified database versions, external checkers, or native dependencies explicitly.
