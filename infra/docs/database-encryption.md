---
type: Deployment Reference
title: Database encryption
description: The customer-managed KMS key and TLS to the database — server-side enforcement, the driver-scoped trust store, and how the smoke proves both.
tags: [kms, tls, encryption]
---

# Database encryption

## Database key

One customer-managed KMS key (`DBKeyArn`, alias `alias/<stack>-database`), with
**automatic rotation enabled**, encrypts RDS storage — and with it the automated
backups and every snapshot — Performance Insights, the RDS-managed master secret
and the six secrets [the stack generates](database-credentials.md). `StorageEncrypted` stays on.

Its key policy allows exactly two things:

- **administration** by `KeyAdminArn` — the IAM role or user that deploys the
  stack, which `deploy.sh` fills from your caller identity (an assumed role's
  session becomes the role's ARN, path included). It may describe, enable,
  disable, tag, rotate, alias, revoke grants on and schedule the deletion of the
  key, and may **not** encrypt, decrypt or create grants;
- **use** by principals of this account only through RDS or Secrets Manager
  (`kms:ViaService`), and only when their own IAM policy also allows it — the
  instance role's allows `kms:Decrypt` through Secrets Manager alone.

The key is **retained when the stack is deleted**: the final snapshot RDS takes
then is encrypted under it, and would be unreadable without it. Schedule its
deletion yourself once that snapshot is no longer needed.

## TLS to the database

- **Server-side (IM8 dp-3):** the stack's own parameter group (family
  `sqlserver-se-16.0`, output `DBParameterGroupName`) sets `rds.force_ssl = 1`,
  and `rds.tls10` and `rds.tls11` to `disabled`, so the server encrypts **every**
  session and none runs below TLS 1.2. **A non-TLS connection is refused:** a
  client that cannot do TLS (its TDS PRELOGIN says `ENCRYPT_NOT_SUP`) is answered
  `ENCRYPT_REQ`, and its plaintext login is dropped unread. A client that merely
  _asks_ for no encryption but can do TLS (`sqlcmd -No`, a JDBC URL with
  `encrypt=false`) is not refused: the server encrypts its session instead. No
  RDS for SQL Server setting refuses that client — the `sqlserver-se-16.0`
  family's TLS parameters are `rds.force_ssl`, `rds.tls10`/`11`/`12`,
  `rds.fips` and the cipher switches, and SQL Server 2022's "force strict
  encryption" (TDS 8.0 only) is not among them — so either way no session to
  this server is ever plaintext.
- **The service:** `DATABASE_URL` is
  `jdbc:sqlserver://<endpoint>:1433;databaseName=<DBName>;encrypt=true;trustStore=/opt/backend/rds-truststore.p12;trustStoreType=PKCS12`,
  and `database.yaml` adds `sslProtocol=TLSv1.2` for every connection. There is
  no `trustServerCertificate`, so the driver validates RDS's certificate chain
  against the trust store and its name against the endpoint.
- **The driver-scoped trust store** (ADR 0013, "The RDS CA trust store is
  driver-scoped"): at boot UserData downloads the region's RDS CA bundle
  (`https://truststore.pki.rds.amazonaws.com/<region>/<region>-bundle.pem`) and
  imports every certificate in it into `/opt/backend/rds-truststore.p12`, a
  PKCS12 store under the password `DBTrustStoreSecretArn` holds (`keytool` reads
  it from its environment, `-storepass:env`). Only the SQL Server driver opens
  it, named by the JDBC URL; `start.sh` and `migrate.sh` hand it its password as
  `SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_TRUSTSTOREPASSWORD`, which Boot
  binds into the driver properties every pool and the migrate step's connection
  are opened with, never into the URL. **The JVM's default trust store is
  untouched** — there is no `-Djavax.net.ssl.trustStore` — so the identity
  provider's discovery document and JWKS, and every other Epic call, keep
  validating against the JDK's own CAs. The service makes those calls on a
  launch's first use, never at startup (ADR 0012), so `smoke.sh` proves it
  directly: the running JVM has no `javax.net.ssl.trustStore` (`jcmd
VM.system_properties`), and the same JDK, as the service user and with its
  default trust store, fetches Epic's discovery document and the JWKS it names
  when Epic Login is on (a public AWS HTTPS endpoint when it is off).
- **The bootstrap's `sqlcmd`:** `-N` (encryption mandatory), **never `-C`**, so
  the ODBC driver validates RDS's certificate chain and its name against the
  endpoint. The RDS CA bundle is given to `sqlcmd` alone, as `SSL_CERT_FILE` in
  its own environment, which the driver's OpenSSL reads; the operating system's
  trust store is not changed, so no other program on the instance gains trust in
  it. Tested against a SQL Server whose certificate only a test CA vouched for:
  without the bundle, with an unrelated CA, or by an address the certificate does
  not name, `sqlcmd -N` refuses with `certificate verify failed`.

`smoke.sh` proves it on every deploy: the parameter group holds the three
values and is in sync on the instance; then, on the instance:

- **a client that cannot do TLS is refused** — `tds-plaintext-probe.py`
  (standard-library Python, piped to the instance's `python3`) speaks TDS
  itself: a PRELOGIN saying `ENCRYPT_NOT_SUP`, then a plaintext LOGIN7 for a
  login that does not exist with a random password, so no credential travels in
  the clear. It passes only when the server drops that login unanswered; a
  server that answers it at all, even with a failed-login error, accepted a
  plaintext session. Against SQL Server 2022 CU27 with forced encryption the
  probe reports `ENCRYPT_REQ` and the dropped login; without it, the server
  answers in plaintext and the probe fails;
- a session that asks for no encryption (`sqlcmd -No`) is encrypted anyway
  (`encrypt_option` `TRUE`), one that cannot validate the certificate is
  refused, and one that validates it connects, encrypted.
