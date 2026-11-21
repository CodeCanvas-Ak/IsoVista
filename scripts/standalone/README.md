# Standalone Test Databases

Docker Compose configurations for local MySQL and PostgreSQL testing. Use these services only as disposable development databases.

## Prerequisites

Install Docker with the Compose v2 plugin. Commands below use `docker compose`, not the legacy `docker-compose` binary.

## Start a database

From this directory:

```bash
# MySQL
docker compose -f mysql/docker-compose.yml up -d

# PostgreSQL
docker compose -f postgres/docker-compose.yml up -d
```

Default development connection settings:

| Service | JDBC URL | Database | User | Password |
| --- | --- | --- | --- | --- |
| MySQL | `jdbc:mysql://127.0.0.1:3306/` | `dbtest` | `dbtest` | `dbtest_pwd` |
| PostgreSQL | `jdbc:postgresql://127.0.0.1:5432/` | `dbtest` | `dbtest` | `dbtest_pwd` |

Select the collector name that matches the Java implementation; see the registration table in [ROADMAP.md](../../ROADMAP.md). IsoVista collectors may create, clear, or drop `dbtest` objects.

## Inspect and stop

```bash
# Follow logs
docker compose -f mysql/docker-compose.yml logs -f
docker compose -f postgres/docker-compose.yml logs -f

# Stop and remove containers; named volumes remain
docker compose -f mysql/docker-compose.yml down
docker compose -f postgres/docker-compose.yml down
```

The Compose files currently use `latest` images and named volumes. Pin database versions for reproducible experiments, and reset persistent data explicitly when a clean initial state is required.
