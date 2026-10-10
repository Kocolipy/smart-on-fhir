package com.example.backend.audit;

import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.OperationalAlerts;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link OperationalAlerts} that keeps what it was told, so a use case's test can assert that an
 * alert was raised — and for what — without reading log bytes.
 */
public final class RecordingOperationalAlerts implements OperationalAlerts {

    private final List<AuditOperation> appendFailures = new ArrayList<>();

    private final List<Class<? extends Throwable>> revocationFailures = new ArrayList<>();

    /** The operation of every audit append reported as failed, in order. */
    public List<AuditOperation> appendFailures() {
        return List.copyOf(appendFailures);
    }

    /** The failure type of every Session revocation reported as failed, in order. */
    public List<Class<? extends Throwable>> revocationFailures() {
        return List.copyOf(revocationFailures);
    }

    @Override
    public void auditAppendFailed(AuditOperation operation, Class<? extends Throwable> failure) {
        appendFailures.add(operation);
    }

    @Override
    public void sessionRevocationFailed(Class<? extends Throwable> failure) {
        revocationFailures.add(failure);
    }
}
