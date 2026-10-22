# Single-thread configuration of the evaluated systems

All systems are compared with **one thread / one core**, so that measured differences come from the
architecture and not from intra-query parallelism.

What is constrained and what is not:

- **Constrained**: intra-query parallelism, number of data partitions, worker/thread pools, CPU cores.
- **Not disabled**: internal optimizations that are part of each system (caches, buffer pools, label scans,
  statistics, query plan caching). They cannot be turned off in general and are part of what a user gets.

> The parameter names below refer to the versions used in the paper. Check them against the documentation
> of the exact version you deploy before running the experiments.

## 1. Every system: limit the container to one core

The strongest, system-independent guarantee: whatever a system does internally (query threads, JVM, garbage
collector), it cannot use more than one core.

```yaml
# docker-compose.yml, in every service of a DBMS (AsterixDB CC/NC, Neo4j, PostgreSQL, ...)
    cpus: 1
```

Verify:

```bash
docker inspect -f '{{.Name}} {{.HostConfig.NanoCpus}}' $(docker ps -q)   # 1000000000 = 1 core
```

Optional, for every JVM-based system (AsterixDB, Neo4j, STGraph graph store), so that they are treated alike:

```
-XX:ParallelGCThreads=1
```

## 2. AsterixDB (STGraph time-series store)

AsterixDB parallelizes over **data partitions** (one per `iodevices` entry of each node controller) and over
the operators of the query plan.

1. One node controller with **one** I/O device, i.e. one data partition:

   ```ini
   [nc/asterix_nc1]
   iodevices=/data/asterix/io0
   ```

   The sample cluster (`start-sample-cluster.sh`) starts more node controllers / partitions: do not use it for
   single-thread runs.

2. Degree of parallelism of the query compiler:

   ```ini
   [common]
   compiler.parallelism=1
   ```

   It can also be set per query:

   ```sql
   SET `compiler.parallelism` "1";
   ```

3. **Re-create the dataverse/dataset after changing the partitioning**: partitions are fixed when a dataset is
   created, so data loaded on a multi-partition cluster stays partitioned.

Verify: the cluster state (`http://<cc-host>:19002/admin/cluster`) must list a single node controller with a
single partition.

## 3. Neo4j

- **Community edition**: each query already runs on a single thread (slotted/pipelined runtime). The parallel
  runtime exists only in the Enterprise edition.
- **Enterprise edition**: limit the parallel runtime, and never use `CYPHER runtime=parallel`.

```properties
# neo4j.conf
# Enterprise only: workers of the parallel runtime
server.cypher.parallel.worker_limit=1
# one Bolt transaction at a time
server.bolt.thread_pool_min_size=1
server.bolt.thread_pool_max_size=1
# optional, same setting as the other JVMs
server.jvm.additional=-XX:ParallelGCThreads=1
```

Verify:

```cypher
CALL dbms.components() YIELD name, edition;   // edition
PROFILE MATCH (p:Person) RETURN count(p);      // "Runtime" must not be PARALLEL
```

## 4. PostgreSQL + TimescaleDB + Apache AGE (AGTS)

```ini
# postgresql.conf (or ALTER SYSTEM SET ...; then SELECT pg_reload_conf();)
max_parallel_workers_per_gather = 0   # no parallel workers in query plans
max_parallel_workers = 0              # no parallel workers at all
max_parallel_maintenance_workers = 0  # no parallel index builds
```

These settings also apply to Cypher queries run through AGE, which execute inside PostgreSQL.

Leave `timescaledb.max_background_workers` at its default: those workers run TimescaleDB jobs (compression,
retention policies), not queries, and are idle unless such jobs are configured.

Verify:

```sql
SHOW max_parallel_workers_per_gather;
SHOW max_parallel_workers;
EXPLAIN SELECT ...;   -- the plan must contain no "Gather" / "Workers Planned"
```

## 5. STGraph (graph store and query engine)

- Run with `threads: [1]` in `src/main/resources/test_config.yml`: the query engine then sends the requests
  to the time-series store from a single worker thread.
- The time-series store is AsterixDB: configure it as in section 2.
- Same container limit (`cpus: 1`) as the other systems.

## Checklist before each run

| System      | Container `cpus: 1` | System setting                                   | Verified with                         |
|-------------|---------------------|--------------------------------------------------|---------------------------------------|
| AsterixDB   | yes                 | 1 NC, 1 `iodevices`, `compiler.parallelism=1`    | `/admin/cluster`                      |
| Neo4j       | yes                 | Community, or `parallel.worker_limit=1`          | `dbms.components()`, `PROFILE`        |
| PostgreSQL  | yes                 | `max_parallel_*workers* = 0`                     | `SHOW ...`, `EXPLAIN` without Gather  |
| STGraph     | yes                 | `threads: [1]`                                   | `test_config.yml`                     |
