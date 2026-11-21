// Rule-test fixture for semgrep/rules/service-security.yml, run by `semgrep --test` from
// scripts/semgrep.sh. It is not compiled and .semgrepignore keeps it out of the scan itself.
// Each `ruleid:` line is a violation the named rules must report; each `ok:` line is a call site
// the named rule must leave alone.
package com.example.backend.fixture;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditSessionRevocationCause;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

class PlantedRecords {

    private static final Logger log = LoggerFactory.getLogger(PlantedRecords.class);

    void openedBelowError() {
        // ruleid: be-log-record-outside-log-event
        log.atInfo().log();
        // ruleid: be-log-record-outside-log-event
        log.atWarn().log();
        // ruleid: be-log-record-outside-log-event
        log.atDebug().log();
        // ruleid: be-log-record-outside-log-event
        log.atTrace().log();
        // ruleid: be-log-record-outside-log-event
        log.info("planted");
        // ruleid: be-log-record-outside-log-event
        log.warn("planted");
        // ruleid: be-log-record-outside-log-event
        log.debug("planted");
        // ruleid: be-log-record-outside-log-event
        log.trace("planted");
    }

    void openedAtError() {
        // ruleid: be-log-record-outside-log-event, be-log-error-without-error-fields
        log.atError().log();
        // ruleid: be-log-record-outside-log-event, be-log-error-without-error-fields
        log.error("planted");
        // ruleid: be-log-record-outside-log-event, be-log-error-without-error-fields
        log.atLevel(Level.ERROR).log();
    }

    void openedAnotherWay() {
        // ruleid: be-log-record-outside-log-event
        log.makeLoggingEventBuilder(Level.INFO).log();
    }

    void aShapesFieldsSetByHand() {
        // ruleid: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER)
                .addKeyValue(LogEvent.OUTCOME, LogEvent.FAILURE)
                .log();
        // ruleid: be-log-record-outside-log-event
        LogEvent.refused(log, Operation.LOGIN, Category.PROCESS, Type.USER)
                .addKeyValue(LogEvent.DURATION_MS, 1L)
                .log();
        // ruleid: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER)
                .setMessage("planted")
                .log();
        // ruleid: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER).log("planted");
    }

    void builtByAShape() {
        // ok: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED).log();
        // ok: be-log-record-outside-log-event
        LogEvent.refused(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.DENIED)
                .addKeyValue(LogEvent.REASON, "planted")
                .log();
        // ok: be-log-error-without-error-fields
        LogEvent.error(log, Operation.AUDIT_APPEND, 500, ErrorCategory.DATABASE,
                        Category.DATABASE, Type.ERROR)
                .log();
        // ok: be-log-record-outside-log-event
        LogEvent.deadlockRetried(log, Operation.SCIM_WRITE).log();
        // ok: be-log-record-outside-log-event
        LogEvent.withError(LogEvent.refused(log, Operation.HTTP_REQUEST_REFUSAL,
                        Category.PROCESS, Type.DENIED), 400, ErrorCategory.DATA, false)
                .log();
    }
}

class PlantedAuditEvents {

    private AuditTrail audit;

    void credentialNamedValuesReachAnEvent(String userName, String password, Object token) {
        // ruleid: be-audit-sensitive-value
        audit.append(new Audited.LoginFailure(userName, AuditRefusalReason.BAD_CREDENTIALS,
                AuditLoginMethod.PASSWORD));
        // ruleid: be-audit-sensitive-value
        Audited built = new Audited.ConnectorTokenRevoked(actor, token.connectorId());
        // ruleid: be-audit-sensitive-value
        audit.append(passwordChangedEvent);
    }

    void stableIdsAndClosedSetsDoNot(java.util.UUID subjectId, java.util.UUID actor,
            String password) {
        // ok: be-audit-sensitive-value
        audit.append(new Audited.LoginFailure(subjectId, AuditRefusalReason.BAD_CREDENTIALS,
                AuditLoginMethod.PASSWORD));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.PasswordChangeRefused(subjectId,
                AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.Logout(actor));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.PasswordChanged(subjectId));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.ConnectorTokenRevoked(actor, subjectId));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.UserSessionsRevoked(actor, subjectId,
                Set.of(AuditUserAttribute.USER_NAME, AuditUserAttribute.PASSWORD),
                Set.of(AuditSessionRevocationCause.PASSWORD_CHANGED), true));
        // ok: be-audit-sensitive-value
        new StringBuilder().append(password);
    }
}

class PlantedTimestampBinds {

    private java.sql.PreparedStatement statement;

    void bindAJvmLocalWallClock(java.time.Instant instant, long millis) throws Exception {
        // ruleid: be-jdbc-timestamp-bind
        jdbc.update("DELETE FROM audit_events WHERE occurred_at < ?", Timestamp.from(instant));
        // ruleid: be-jdbc-timestamp-bind
        jdbc.update("DELETE FROM audit_events WHERE occurred_at < ?", new Timestamp(millis));
        // ruleid: be-jdbc-timestamp-bind
        Object parsed = Timestamp.valueOf("2026-10-14 09:30:00");
        // ruleid: be-jdbc-timestamp-bind
        statement.setTimestamp(1, null);
        // ruleid: be-jdbc-timestamp-bind
        parameters.addValue("from", null, Types.TIMESTAMP);
        // ruleid: be-jdbc-timestamp-bind
        Object qualified = java.sql.Timestamp.from(instant);
    }

    void bindAnOffsetAtUtcAndReadEitherWay(java.time.Instant instant, java.sql.ResultSet row)
            throws Exception {
        // ok: be-jdbc-timestamp-bind
        jdbc.update("DELETE FROM audit_events WHERE occurred_at < ?",
                instant.atOffset(java.time.ZoneOffset.UTC));
        // ok: be-jdbc-timestamp-bind
        parameters.addValue("from", instant.atOffset(java.time.ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
        // ok: be-jdbc-timestamp-bind
        java.time.Instant read = row.getTimestamp("occurred_at").toInstant();
        // ok: be-jdbc-timestamp-bind
        java.time.OffsetDateTime at = row.getObject("occurred_at", java.time.OffsetDateTime.class);
    }
}

class PlantedPatientValues {

    private static final Logger log = LoggerFactory.getLogger(PlantedPatientValues.class);

    void nricNamedValuesReachARecord(Patient patient, String nric, String patientNric) {
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.APPLICATION_STARTUP, Category.PROCESS, Type.START)
                .addKeyValue("patient.nric", patient.nric())
                .log();
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.APPLICATION_STARTUP, Category.PROCESS, Type.START)
                .addKeyValue(LogEvent.REASON, nric)
                .log();
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.APPLICATION_STARTUP, Category.PROCESS, Type.START)
                .addKeyValue(LogEvent.REASON, patientNric)
                .log();
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.APPLICATION_STARTUP, Category.PROCESS, Type.START)
                .addKeyValue(LogEvent.REASON, NRIC_VALUE)
                .log();
    }

    void theIdAndAnEnrichedValueDoNot(Patient patient, String enrichment) {
        // ok: be-log-sensitive-value
        LogEvent.success(log, Operation.APPLICATION_STARTUP, Category.PROCESS, Type.START)
                .addKeyValue("patient.id", patient.id())
                .log();
        // ok: be-log-sensitive-value
        LogEvent.success(log, Operation.APPLICATION_STARTUP, Category.PROCESS, Type.START)
                .addKeyValue(LogEvent.REASON, enrichment)
                .log();
    }
}

class PlantedChainSettings {

    void protectionTurnedOff(HttpSecurity http) throws Exception {
        // ruleid: be-csrf-disabled
        http.csrf(csrf -> csrf.disable());
        // ruleid: be-csrf-disabled
        http.csrf(AbstractHttpConfigurer::disable);
        // ruleid: be-csrf-disabled
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        // ruleid: be-csrf-cookie-token
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        // ruleid: be-csrf-cookie-token
        CsrfTokenRepository cookies = new CookieCsrfTokenRepository();
        // ruleid: be-csrf-cookie-token
        CsrfTokenRequestHandler unmasked = new SpaCsrfTokenRequestHandler();
        // ruleid: be-csrf-cookie-token
        http.csrf(csrf -> csrf.spa());
        // ruleid: be-authorize-any-request-permit-all
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        // ruleid: be-session-fixation-disabled
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.none()));
    }

    void protectionKept(HttpSecurity http) throws Exception {
        // ok: be-csrf-disabled
        http.csrf(csrf -> csrf.csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()));
        // ok: be-csrf-cookie-token
        CsrfTokenRepository sessions = new HttpSessionCsrfTokenRepository();
        // ok: be-authorize-any-request-permit-all
        http.authorizeHttpRequests(requests -> requests.requestMatchers("/api/auth/login")
                .permitAll().anyRequest().authenticated());
        // ok: be-session-fixation-disabled
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
    }
}

class PlantedCredentials {

    void weakOrLiteral(Client client) {
        // ruleid: be-insecure-password-encoder
        PasswordEncoder plain = NoOpPasswordEncoder.getInstance();
        // ruleid: be-insecure-password-encoder
        PasswordEncoder digest = new MessageDigestPasswordEncoder("SHA-256");
        // ruleid: be-insecure-password-encoder
        PasswordEncoder standard = new StandardPasswordEncoder();
        // ruleid: be-hardcoded-credential-literal
        UserDetails planted = User.withUsername("planted").password("planted-password").build();
        // ruleid: be-hardcoded-credential-literal
        client.secret("planted-secret");
    }

    void strongOrExternal(Client client, String configuredPassword, String configuredSecret) {
        // ok: be-insecure-password-encoder
        PasswordEncoder bcrypt = new BCryptPasswordEncoder();
        // ok: be-hardcoded-credential-literal
        UserDetails configured = User.withUsername("planted").password(configuredPassword).build();
        // ok: be-hardcoded-credential-literal
        client.secret(configuredSecret);
    }
}

@SpringBootTest
class PlantedTestCredentials {

    void aTestContextsOwnData(Client client) {
        // ok: be-hardcoded-credential-literal
        client.password("test-password");
    }
}

interface PlantedQueries {

    // ruleid: be-jpql-string-concatenation
    @Query("SELECT u FROM ScimUserEntity u WHERE u.userName = " + NAME)
    List<Object> concatenated();

    // ok: be-jpql-string-concatenation
    @Query("SELECT u FROM ScimUserEntity u WHERE u.userName = :name")
    List<Object> bound(String name);
}

class PlantedQueryCalls {

    void concatenated(EntityManager entities, JdbcTemplate jdbc, String name) {
        // ruleid: be-jpql-string-concatenation
        entities.createQuery("SELECT u FROM ScimUserEntity u WHERE u.userName = " + name);
        // ruleid: be-jpql-string-concatenation
        entities.createNativeQuery("SELECT id FROM scim_users WHERE user_name = " + name);
        // ruleid: be-jpql-string-concatenation
        jdbc.queryForObject("SELECT id FROM scim_users WHERE user_name = " + name, UUID.class);
        // ruleid: be-jpql-string-concatenation
        jdbc.query("SELECT id FROM scim_users WHERE user_name = " + name, mapper);
    }

    void bound(EntityManager entities, JdbcTemplate jdbc, String name) {
        // ok: be-jpql-string-concatenation
        entities.createQuery("SELECT u FROM ScimUserEntity u WHERE u.userName = :name");
        // ok: be-jpql-string-concatenation
        jdbc.query("SELECT id FROM scim_users WHERE user_name = ?", mapper, name);
    }
}

class PlantedCors {

    void anyOrigin(CorsRegistry registry, CorsConfiguration configuration) {
        // ruleid: be-cors-wildcard-origin
        registry.addMapping("/**").allowedOrigins("*");
        // ruleid: be-cors-wildcard-origin
        registry.addMapping("/**").allowedOriginPatterns("*");
        // ruleid: be-cors-wildcard-origin
        configuration.addAllowedOrigin("*");
    }

    void oneOrigin(CorsConfiguration configuration) {
        // ok: be-cors-wildcard-origin
        configuration.addAllowedOrigin("https://ehr.example.com");
    }

    // ruleid: be-cors-wildcard-origin
    @CrossOrigin(origins = "*")
    void anyOriginHandler() {
    }

    // ok: be-cors-wildcard-origin
    @CrossOrigin(origins = "https://ehr.example.com")
    void oneOriginHandler() {
    }
}

class PlantedProcessesAndStreams {

    void spawnAndDeserialize(String command, InputStream input, ObjectInputStream stream)
            throws Exception {
        // ruleid: be-process-execution
        Runtime.getRuntime().exec(command);
        // ruleid: be-process-execution
        Process started = new ProcessBuilder("sh", "-c", command).start();
        // ruleid: be-java-deserialization
        Object read = new ObjectInputStream(input).readObject();
        // ruleid: be-java-deserialization
        Object streamed = stream.readObject();
    }

    void neither(ObjectMapper mapper, InputStream input) throws Exception {
        // ok: be-process-execution
        int processors = Runtime.getRuntime().availableProcessors();
        // ok: be-java-deserialization
        Object parsed = mapper.readValue(input, Object.class);
    }
}

class PlantedTrustAll implements X509TrustManager, HostnameVerifier {

    // ruleid: be-trust-all-certificates
    public void checkServerTrusted(X509Certificate[] chain, String authType) { }

    // ruleid: be-trust-all-certificates
    public boolean verify(String host, SSLSession session) { return true; }

    void noVerification(HttpClientBuilder builder) {
        // ruleid: be-trust-all-certificates
        builder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
    }
}

class PlantedTrustChecked implements X509TrustManager, HostnameVerifier {

    // ok: be-trust-all-certificates
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException { delegate.checkServerTrusted(chain, authType); }

    // ok: be-trust-all-certificates
    public boolean verify(String host, SSLSession session) { return expected.equals(host); }

    void defaultVerification(HttpClientBuilder builder) {
        // ok: be-trust-all-certificates
        builder.setSSLHostnameVerifier(new DefaultHostnameVerifier());
    }
}

class PlantedLogFields {

    private static final Logger log = LoggerFactory.getLogger(PlantedLogFields.class);

    void writtenByHand(java.util.UUID id, String reason) {
        // ruleid: be-log-message-concatenation, be-log-record-outside-log-event
        log.info("planted " + id);
        // ruleid: be-log-message-concatenation, be-log-record-outside-log-event
        log.warn(reason + " planted");
        // ruleid: be-mdc-direct-access
        MDC.put("user.id", id.toString());
        // ruleid: be-mdc-direct-access
        org.slf4j.MDC.setContextMap(java.util.Map.of());
        // ruleid: be-log-event-action-outside-the-vocabulary
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED)
                .addKeyValue(LogEvent.ACTION, "planted")
                .log();
        // ruleid: be-log-event-action-outside-the-vocabulary
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED)
                .addKeyValue("app.event.action", "planted")
                .log();
    }

    void writtenAsFields(java.util.UUID id, String reason) {
        // ok: be-log-message-concatenation
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED)
                .addKeyValue(LogEvent.REASON, "planted " + reason)
                .log();
        // ok: be-mdc-direct-access
        String held = MDC.get("user.id");
        // ok: be-log-event-action-outside-the-vocabulary
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED)
                .addKeyValue(LogEvent.REASON, "planted")
                .log();
    }
}

class PlantedRetentionRole {

    void assumed(JdbcTemplate jdbc) {
        // ruleid: be-audit-retention-role-outside-its-adapter
        jdbc.execute("SET ROLE backend_audit_retention");
    }

    void notAssumed(JdbcTemplate jdbc) {
        // ok: be-audit-retention-role-outside-its-adapter
        jdbc.execute("SELECT COUNT(*) FROM audit_events");
    }
}

class PlantedScimCredentialReads {

    void outsideTheHeader(HttpServletRequest request) {
        // ruleid: be-scim-credential-read-outside-the-authorization-header
        String parameter = request.getParameter("access_token");
        // ruleid: be-scim-credential-read-outside-the-authorization-header
        Object parameters = request.getParameterMap();
        // ruleid: be-scim-credential-read-outside-the-authorization-header
        String[] values = request.getParameterValues("access_token");
        // ruleid: be-scim-credential-read-outside-the-authorization-header
        Cookie[] cookies = request.getCookies();
        // ruleid: be-scim-credential-read-outside-the-authorization-header
        String query = request.getQueryString();
    }

    void theHeader(HttpServletRequest request) {
        // ok: be-scim-credential-read-outside-the-authorization-header
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    }
}
