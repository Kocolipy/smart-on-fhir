# Sourced, not run: one Redis for every test JVM of a gate run.
#
# Without it each Surefire fork starts a Redis container of its own, and the
# Testcontainers start behind it is part of every fork's first context boot. A
# gate that sources this file and calls start_test_redis instead starts one
# Redis for the run and exports TEST_REDIS_HOST and TEST_REDIS_PORT, which every
# fork inherits; ContainerTestConfiguration then gives each context its own
# numbered database on that Redis rather than starting another. A context takes
# its number with an INCR on the counter in database 0, which no context uses,
# so contexts in different JVMs never share a number. The container is removed
# when the calling shell exits, however it exits short of SIGKILL; a leftover
# carries the label below (docker ps --filter label=smart-on-fhir.test-redis).
#
# bash 3.2 compatible, like the scripts that source it.

# The image compose.yaml's `redis` service runs, so the pin is read from the
# file /README.md ("Toolchain pins") names rather than copied.
test_redis_image() {
  local image
  image=$(grep -m1 -E '^[[:space:]]+image: redis:' compose.yaml |
    sed -E 's/^[[:space:]]+image:[[:space:]]+//')
  [ -n "$image" ] || { echo "test-redis: no Redis image in compose.yaml" >&2; return 1; }
  printf '%s\n' "$image"
}

start_test_redis() {
  local image container port_line deadline previous_trap
  image=$(test_redis_image) || return 1

  echo "== starting the shared test Redis =="
  # As many numbered databases as ContainerTestConfiguration's own Redis has:
  # Redis' default of 16 is fewer than the suite's contexts.
  container=$(docker run -d --rm \
    --label smart-on-fhir.test-redis \
    -p 127.0.0.1::6379 \
    "$image" redis-server --databases 128) || return 1
  TEST_REDIS_CONTAINER="$container"
  # Chained onto any EXIT trap already set (the shared SQL Server's), so
  # sourcing both helpers removes both containers.
  previous_trap=$(trap -p EXIT)
  if [ -n "$previous_trap" ]; then
    eval "set -- $previous_trap"
    trap "stop_test_redis; $3" EXIT
  else
    trap stop_test_redis EXIT
  fi
  trap 'exit 130' INT
  trap 'exit 143' TERM

  deadline=$((SECONDS + 60))
  until docker exec "$container" redis-cli ping 2>/dev/null | grep -q PONG; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "test-redis: Redis did not answer within 60 seconds" >&2
      return 1
    fi
    sleep 1
  done

  port_line=$(docker port "$container" 6379/tcp | head -n1)
  export TEST_REDIS_HOST=127.0.0.1
  export TEST_REDIS_PORT="${port_line##*:}"
}

stop_test_redis() {
  if [ -n "${TEST_REDIS_CONTAINER:-}" ]; then
    docker rm -f "$TEST_REDIS_CONTAINER" >/dev/null 2>&1 || true
    TEST_REDIS_CONTAINER=
  fi
}
