# Sourced, not run: one SQL Server for every test JVM of a gate run.
#
# Each Surefire fork used to start a SQL Server container of its own, and the
# forks' servers together outgrew a developer machine's memory. A gate that
# sources this file and calls start_test_sql_server instead starts one server
# for the run, creates the three database logins on it, and exports the
# TEST_SQL_SERVER_* variables, which every fork inherits;
# ContainerTestConfiguration then gives each context its own database on that
# server rather than starting another. The server is removed when the calling
# shell exits, however it exits short of SIGKILL; a leftover carries the label
# below (docker ps --filter label=smart-on-fhir.test-sql-server).
#
# No secret is ever echoed or put on a command line: every password reaches
# docker as a bare `-e NAME` read from this shell's environment, and the logins'
# reach their CREATE LOGIN as sqlcmd scripting variables read from the same.
#
# bash 3.2 compatible, like the scripts that source it.

# The image compose.yaml's `sqlserver` service runs, so the pin is read from
# the file /README.md ("Toolchain pins") names rather than copied a third time.
test_sql_server_image() {
  local image
  image=$(grep -m1 -E '^[[:space:]]+image: mcr\.microsoft\.com/mssql/server:' compose.yaml |
    sed -E 's/^[[:space:]]+image:[[:space:]]+//')
  [ -n "$image" ] || { echo "test-sql-server: no SQL Server image in compose.yaml" >&2; return 1; }
  printf '%s\n' "$image"
}

# Generated per run and never written anywhere. A UUID meets SQL Server's
# password policy (lower-case letters, digits and a symbol) and holds no quote.
test_sql_server_password() {
  cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]'
}

# sqlcmd as sa inside the container, with any extra variables named by -e.
test_sql_server_sqlcmd() {
  SQLCMDPASSWORD="$TEST_SQL_SERVER_SA_PASSWORD" docker exec -e SQLCMDPASSWORD "$@"
}

start_test_sql_server() {
  local image container port_line sqlcmd=/opt/mssql-tools18/bin/sqlcmd deadline
  image=$(test_sql_server_image) || return 1

  TEST_SQL_SERVER_SA_PASSWORD=$(test_sql_server_password)
  TEST_SQL_SERVER_OWNER_PASSWORD=$(test_sql_server_password)
  TEST_SQL_SERVER_APP_PASSWORD=$(test_sql_server_password)
  TEST_SQL_SERVER_RETENTION_PASSWORD=$(test_sql_server_password)
  export TEST_SQL_SERVER_SA_PASSWORD TEST_SQL_SERVER_OWNER_PASSWORD \
    TEST_SQL_SERVER_APP_PASSWORD TEST_SQL_SERVER_RETENTION_PASSWORD

  echo "== starting the shared test SQL Server =="
  # The memory cap is compose.yaml's: SQL Server otherwise takes what memory it
  # can see, which is most of a developer machine's.
  container=$(MSSQL_SA_PASSWORD="$TEST_SQL_SERVER_SA_PASSWORD" docker run -d --rm \
    --platform linux/amd64 \
    --label smart-on-fhir.test-sql-server \
    -e ACCEPT_EULA=Y -e MSSQL_PID=Developer -e MSSQL_MEMORY_LIMIT_MB=2048 \
    -e MSSQL_SA_PASSWORD \
    -p 127.0.0.1::1433 \
    "$image") || return 1
  TEST_SQL_SERVER_CONTAINER="$container"
  trap stop_test_sql_server EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  deadline=$((SECONDS + 120))
  until test_sql_server_sqlcmd "$container" "$sqlcmd" -S localhost -U sa -C -b -l 5 \
    -Q 'SELECT 1' -o /dev/null >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "test-sql-server: the server did not accept a connection within 120 seconds" >&2
      return 1
    fi
    sleep 2
  done

  # The logins ContainerTestConfiguration names, as it creates them on a server
  # of its own.
  OWNER_PASSWORD="$TEST_SQL_SERVER_OWNER_PASSWORD" APP_PASSWORD="$TEST_SQL_SERVER_APP_PASSWORD" \
    RETENTION_PASSWORD="$TEST_SQL_SERVER_RETENTION_PASSWORD" \
    test_sql_server_sqlcmd -e OWNER_PASSWORD -e APP_PASSWORD -e RETENTION_PASSWORD \
    "$container" "$sqlcmd" -S localhost -U sa -C -b -Q "
      CREATE LOGIN backend_owner WITH PASSWORD = N'\$(OWNER_PASSWORD)';
      CREATE LOGIN backend_app_login WITH PASSWORD = N'\$(APP_PASSWORD)';
      CREATE LOGIN backend_retention_login WITH PASSWORD = N'\$(RETENTION_PASSWORD)';" ||
    { echo "test-sql-server: the test database logins could not be created" >&2; return 1; }

  port_line=$(docker port "$container" 1433/tcp | head -n1)
  export TEST_SQL_SERVER_HOST=127.0.0.1
  export TEST_SQL_SERVER_PORT="${port_line##*:}"
}

stop_test_sql_server() {
  if [ -n "${TEST_SQL_SERVER_CONTAINER:-}" ]; then
    docker rm -f "$TEST_SQL_SERVER_CONTAINER" >/dev/null 2>&1 || true
    TEST_SQL_SERVER_CONTAINER=
  fi
}
