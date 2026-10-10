package com.example.backend.auth;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.application.EpicCallCounts;
import com.example.backend.auth.application.LoginAttemptService;
import com.example.backend.auth.application.LoginCounts;
import com.example.backend.auth.application.LoginOutcomeService;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.util.ArrayList;
import java.util.List;

/**
 * A Login's counts for tests, standing in for the Micrometer meters: each count moved, in order,
 * spelled as the meters' series are — {@code password:success}, {@code sso:refused:INVALID_STATE},
 * {@code sso:unavailable}, {@code failed_call:token:cert/auth} — so a test asserts exactly which
 * counts an ending moved.
 */
public final class RecordingLoginCounts implements LoginCounts, EpicCallCounts {

    private final List<String> moved = new ArrayList<>();

    /**
     * A {@link LoginOutcomeService} over {@code attempts} and the trail {@code audit} whose counts
     * nobody reads.
     */
    public static LoginOutcomeService uncounted(LoginAttemptService attempts, AuditTrail audit) {
        RecordingLoginCounts counts = new RecordingLoginCounts();
        return new LoginOutcomeService(attempts, audit, counts, counts);
    }

    @Override
    public void signedIn(AuditLoginMethod method) {
        moved.add(method.value() + ":success");
    }

    @Override
    public void refused(AuditLoginMethod method, AuditRefusalReason reason) {
        moved.add(method.value() + ":refused:" + reason.name());
    }

    @Override
    public void unavailable() {
        moved.add("sso:unavailable");
    }

    @Override
    public void failedCall(String call, ErrorCategory category) {
        moved.add("failed_call:" + call + ":" + category.value());
    }

    /** Every count moved so far, in order. */
    public List<String> moved() {
        return List.copyOf(moved);
    }
}
