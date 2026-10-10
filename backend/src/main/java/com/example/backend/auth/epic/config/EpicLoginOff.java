package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicLogin;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;

/**
 * Epic Login off — what a deployment gets by setting nothing: the release gate, and only that.
 *
 * <p>The gate is ordered first, as the SCIM release gate is in its chain, so every request under
 * {@code /api/auth/epic} is answered {@code 404} before any session, CSRF or authorization filter
 * decides anything, and the routes cannot be probed for existence. No Epic rule is added: no
 * request under the namespace gets past the gate to be decided by one.
 */
public final class EpicLoginOff implements EpicLogin {

    @Override
    public void applyTo(HttpSecurity http) {
        http.addFilterBefore(new EpicReleaseGateFilter(), WebAsyncManagerIntegrationFilter.class);
    }

    /** Off, and nothing more: there are no signing keys to name. */
    @Override
    public LoggingEventBuilder addStartupFields(LoggingEventBuilder record) {
        return record.addKeyValue(ENABLED_FIELD, false);
    }
}
