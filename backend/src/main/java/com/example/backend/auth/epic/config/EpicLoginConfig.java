package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicDevAllowances;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicLoginProperties;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicSigningKeys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Where the Epic Login switch and its configuration enter the application.
 *
 * <p>With {@code APP_EPIC_ENABLED} off — the default — nothing but the switch is read: there are
 * no {@link EpicLoginSettings} and no {@link EpicSigningKeys}, so a deployment that does not serve
 * Epic Login starts with no Epic variables at all, and the {@link EpicLogin} wired is the off
 * adapter, the release gate alone. With it on, the
 * configuration is validated here, as the settings are built, so a missing or malformed setting
 * fails startup rather than the first clinician's launch. Startup does not contact Epic:
 * discovery runs on first use (D26).
 */
@Configuration
@EnableConfigurationProperties(EpicLoginProperties.class)
public class EpicLoginConfig {

    /**
     * The one profile granted the local Docker launcher's relaxations: {@code http} URLs (D21)
     * and the relative {@code fhirUser} {@code Practitioner/{id}}.
     */
    static final String DEV_PROFILE = "dev";

    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public EpicLoginSettings epicLoginSettings(
            EpicLoginProperties properties, Environment environment) {
        return properties.validate(environment.acceptsProfiles(Profiles.of(DEV_PROFILE))
                ? EpicDevAllowances.LOCAL_LAUNCHER
                : EpicDevAllowances.NONE);
    }

    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public EpicSigningKeys epicSigningKeys(EpicLoginSettings settings) {
        return settings.signingKeys();
    }
}
