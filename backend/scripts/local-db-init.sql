-- The local SQL Server's database and its three logins, created the way the
-- deployed bootstrap creates them (ADR 0015). Run by the one-shot `sqlserver-init`
-- service in compose.yaml, through scripts/local-db-init.sh, as `sa`.
--
-- Idempotent: a database or login that exists is left in place, and each login's
-- password is reset from backend/.env, so running it again applies a changed .env.
--
-- Every value arrives as a sqlcmd scripting variable read from the environment,
-- never from a command-line argument, and sqlcmd runs without echoing its input,
-- so no password reaches a process list or the container's log.
-- local-db-init.sh has already refused a login name outside
-- ^[a-z][a-z0-9_]{2,30}$, a database name outside ^[a-z][a-z0-9_]{0,63}$ and a
-- password holding a quote, so each value is safe inside the literal below; the
-- names are wrapped with QUOTENAME and the passwords escaped all the same.
--
-- What it sets is what no migration can: the database's collation, its
-- compatibility level, and read-committed snapshot with snapshot isolation —
-- `ALTER DATABASE … SET READ_COMMITTED_SNAPSHOT` cannot run inside the
-- transaction Flyway migrates in. The roles, the database users of the app and
-- retention logins, and their grants are the V1 migration's.
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @database SYSNAME = N'$(DATABASE_NAME)';
DECLARE @logins TABLE (name SYSNAME NOT NULL, password NVARCHAR(128) NOT NULL);
INSERT INTO @logins (name, password) VALUES
    (N'$(DATABASE_OWNER_USERNAME)', N'$(DATABASE_OWNER_PASSWORD)'),
    (N'$(DATABASE_USERNAME)', N'$(DATABASE_PASSWORD)'),
    (N'$(DATABASE_RETENTION_USERNAME)', N'$(DATABASE_RETENTION_PASSWORD)');

DECLARE @name SYSNAME, @password NVARCHAR(128), @ddl NVARCHAR(MAX);
DECLARE login_cursor CURSOR LOCAL FAST_FORWARD FOR SELECT name, password FROM @logins;
OPEN login_cursor;
FETCH NEXT FROM login_cursor INTO @name, @password;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @ddl = CASE WHEN SUSER_ID(@name) IS NULL THEN N'CREATE' ELSE N'ALTER' END
        + N' LOGIN ' + QUOTENAME(@name)
        + N' WITH PASSWORD = N''' + REPLACE(@password, N'''', N'''''') + N'''';
    EXEC (@ddl);
    FETCH NEXT FROM login_cursor INTO @name, @password;
END;
CLOSE login_cursor;
DEALLOCATE login_cursor;

IF DB_ID(@database) IS NULL
BEGIN
    SET @ddl = N'CREATE DATABASE ' + QUOTENAME(@database)
        + N' COLLATE Latin1_General_100_BIN2_UTF8';
    EXEC (@ddl);
END;

SET @ddl = N'ALTER DATABASE ' + QUOTENAME(@database) + N' SET COMPATIBILITY_LEVEL = 160;';
IF (SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = @database) = 0
    SET @ddl += N' ALTER DATABASE ' + QUOTENAME(@database)
        + N' SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;';
IF (SELECT snapshot_isolation_state FROM sys.databases WHERE name = @database) = 0
    SET @ddl += N' ALTER DATABASE ' + QUOTENAME(@database) + N' SET ALLOW_SNAPSHOT_ISOLATION ON;';
-- The owner login owns the database, as the deployed bootstrap leaves it, and
-- runs the migrations as its owner.
SET @ddl += N' ALTER AUTHORIZATION ON DATABASE::' + QUOTENAME(@database)
    + N' TO ' + QUOTENAME(N'$(DATABASE_OWNER_USERNAME)') + N';';
EXEC (@ddl);

SELECT CONCAT(N'local-db-init: ', name COLLATE DATABASE_DEFAULT, N' (',
              collation_name COLLATE DATABASE_DEFAULT, N', level ', compatibility_level,
              N', RCSI ', is_read_committed_snapshot_on,
              N', snapshot ', snapshot_isolation_state_desc COLLATE DATABASE_DEFAULT,
              N') is ready')
  FROM sys.databases
 WHERE name = @database;
