package com.example.backend.auth;

import com.example.backend.auth.application.EpicLoginCounts;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.util.ArrayList;
import java.util.List;

/**
 * Epic Login's counts for tests, standing in for the Micrometer meters: each count moved, in
 * order, spelled as the meters' series are — {@code success}, {@code refused:INVALID_STATE},
 * {@code unavailable}, {@code failed_call:token:cert/auth} — so a test asserts exactly which
 * counts an ending moved.
 */
public final class RecordingEpicLoginCounts implements EpicLoginCounts {

    private final List<String> moved = new ArrayList<>();

    @Override
    public void signedIn() {
        moved.add("success");
    }

    @Override
    public void refused(EpicLoginFailureReason reason) {
        moved.add("refused:" + reason.name());
    }

    @Override
    public void unavailable() {
        moved.add("unavailable");
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
