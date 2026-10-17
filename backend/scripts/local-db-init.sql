-- The local SQL Server's database and its three logins, created the way the
-- deployed bootstrap creates them (ADR 0013). Run by the one-shot `sqlserver-init`
-- service in compose.yaml, through scripts/local-db-init.sh, as `sa`.
--
-- Everything from `SET NOCOUNT ON` down is, line for line, the deployed stack's
-- /opt/backend/db-bootstrap.sql (infra/infrastructure.yaml, cfn-init), which RDS's
-- master user runs; infra/check-bootstrap-parity.sh fails when the two differ, so
-- change both together. infra/docs/database-boot.md, "Database bootstrap", explains each step.
--
-- Idempotent: what exists is left in place, so a run that finds everything in
-- place changes nothing; every change prints a `db-init: change:` line. It
-- creates a missing login with its password, and resets the password of an
-- existing login named in RESET_LOGINS: the calling script tries each login's
-- password first and names, `|`-separated (`|` when none), each one that did
-- not sign in.
--
-- Every value arrives as a sqlcmd scripting variable read from the environment,
-- never from a command-line argument, and sqlcmd runs without echoing its input,
-- so no password reaches a process list or the container's log.
-- local-db-init.sh has already refused a login name outside
-- ^[a-z][a-z0-9_]{2,30}$, a database name outside ^[a-z][a-z0-9_]{0,63}$ and a
-- password holding a quote; the names are wrapped with QUOTENAME and the
-- passwords escaped all the same.
--
-- What it sets is what no migration can: the database's collation, its
-- compatibility level, and read-committed snapshot with snapshot isolation —
-- `ALTER DATABASE … SET READ_COMMITTED_SNAPSHOT` cannot run inside the
-- transaction Flyway migrates in. The owner login becomes a member of db_owner,
-- not the database's owner, as on RDS. The roles, the database users of the app
-- and retention logins, and their grants are the V1 migration's.
SET NOCOUNT ON;
SET XACT_ABORT ON;
DECLARE @database SYSNAME = N'$(DATABASE_NAME)', @owner SYSNAME = N'$(DATABASE_OWNER_USERNAME)',
    @collation SYSNAME = N'Latin1_General_100_BIN2_UTF8', @name SYSNAME, @password NVARCHAR(128),
    @ddl NVARCHAR(MAX);
DECLARE @logins TABLE (name SYSNAME, password NVARCHAR(128));
INSERT INTO @logins VALUES (@owner, N'$(DATABASE_OWNER_PASSWORD)'),
    (N'$(DATABASE_USERNAME)', N'$(DATABASE_PASSWORD)'),
    (N'$(DATABASE_RETENTION_USERNAME)', N'$(DATABASE_RETENTION_PASSWORD)');
-- A missing login is created; an existing one keeps its password unless the
-- calling script found that it no longer signs in (RESET_LOGINS).
WHILE EXISTS (SELECT 1 FROM @logins)
BEGIN
    SELECT TOP (1) @name = name, @password = password FROM @logins;
    DELETE FROM @logins WHERE name = @name;
    IF SUSER_ID(@name) IS NULL
    BEGIN
        SET @ddl = N'CREATE LOGIN ' + QUOTENAME(@name) + N' WITH PASSWORD = N'''
            + REPLACE(@password, N'''', N'''''') + N'''';
        EXEC (@ddl);
        PRINT N'db-init: change: created login ' + @name;
    END
    ELSE IF CHARINDEX(N'|' + @name + N'|', N'$(RESET_LOGINS)') > 0
    BEGIN
        SET @ddl = N'ALTER LOGIN ' + QUOTENAME(@name) + N' WITH PASSWORD = N'''
            + REPLACE(@password, N'''', N'''''') + N'''';
        EXEC (@ddl);
        PRINT N'db-init: change: reset the password of login ' + @name;
    END;
END;
-- The collation set here is the database's; the server's is fixed when the
-- server is created (ADR 0013). A database found with another one is
-- refused: altering it would not convert its columns.
IF DB_ID(@database) IS NULL
BEGIN
    SET @ddl = N'CREATE DATABASE ' + QUOTENAME(@database) + N' COLLATE ' + @collation;
    EXEC (@ddl);
    PRINT N'db-init: change: created database ' + @database;
END;
IF (SELECT collation_name FROM sys.databases WHERE name = @database) <> @collation
    THROW 50000, N'db-init: the database exists with another collation', 1;
DECLARE @options TABLE (setting NVARCHAR(100));
INSERT INTO @options
SELECT N'COMPATIBILITY_LEVEL = 160' FROM sys.databases
 WHERE name = @database AND compatibility_level <> 160
UNION ALL SELECT N'READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE' FROM sys.databases
 WHERE name = @database AND is_read_committed_snapshot_on = 0
UNION ALL SELECT N'ALLOW_SNAPSHOT_ISOLATION ON' FROM sys.databases
 WHERE name = @database AND snapshot_isolation_state = 0;
WHILE EXISTS (SELECT 1 FROM @options)
BEGIN
    SELECT TOP (1) @name = setting FROM @options;
    DELETE FROM @options WHERE setting = @name;
    SET @ddl = N'ALTER DATABASE ' + QUOTENAME(@database) + N' SET ' + @name;
    EXEC (@ddl);
    PRINT N'db-init: change: set ' + @name;
END;
-- The owner login: a database user of its own name, in db_owner. A login
-- already mapped into the database (as dbo, or by another name) keeps it.
SET @ddl = N'USE ' + QUOTENAME(@database) + N';
DECLARE @owner SYSNAME = N''' + REPLACE(@owner, N'''', N'''''') + N''';
DECLARE @id INT = (SELECT principal_id FROM sys.database_principals WHERE sid = SUSER_SID(@owner)),
    @do NVARCHAR(400);
IF @id IS NULL
BEGIN
    SET @do = N''CREATE USER '' + QUOTENAME(@owner) + N'' FOR LOGIN '' + QUOTENAME(@owner)
        + N'' WITH DEFAULT_SCHEMA = dbo'';
    EXEC (@do);
    SET @id = DATABASE_PRINCIPAL_ID(@owner);
    PRINT N''db-init: change: created database user '' + @owner;
END;
IF @id <> DATABASE_PRINCIPAL_ID(N''dbo'') AND IS_ROLEMEMBER(N''db_owner'', USER_NAME(@id)) = 0
BEGIN
    SET @do = N''ALTER ROLE db_owner ADD MEMBER '' + QUOTENAME(USER_NAME(@id));
    EXEC (@do);
    PRINT N''db-init: change: added '' + @owner + N'' to db_owner'';
END;';
EXEC (@ddl);
SELECT CONCAT(N'db-init: ', name COLLATE DATABASE_DEFAULT, N' (',
              collation_name COLLATE DATABASE_DEFAULT, N', level ', compatibility_level,
              N', RCSI ', is_read_committed_snapshot_on,
              N', snapshot ', snapshot_isolation_state_desc COLLATE DATABASE_DEFAULT,
              N') is ready')
  FROM sys.databases
 WHERE name = @database;
