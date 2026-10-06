package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicLoginProperties;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicReleaseGate;
import com.example.backend.auth.epic.EpicSigningKeys;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Where the Epic Login switch and its configuration enter the application.
 *
 * <p>With {@code APP_EPIC_ENABLED} off — the default — the gate is closed and nothing else is
 * read: there are no {@link EpicLoginSettings} and no {@link EpicSigningKeys}, so a deployment
 * that does not serve Epic Login starts with no Epic variables at all. With it on, the
 * configuration is validated here, as the settings are built, so a missing or malformed setting
 * fails startup rather than the first clinician's launch. Startup does not contact Epic:
 * discovery runs on first use (D26).
 */
@Configuration
@EnableConfigurationProperties(EpicLoginProperties.class)
public class EpicLoginConfig {

    /** The one profile allowed {@code http} URLs, for the local Docker launcher (D21). */
    static final String DEV_PROFILE = "dev";

    @Bean
    @Conditional(EpicLoginEnabled.class)
    public EpicLoginSettings epicLoginSettings(
            EpicLoginProperties properties, Environment environment) {
        return properties.validate(environment.acceptsProfiles(Profiles.of(DEV_PROFILE)));
    }

    @Bean
    @Conditional(EpicLoginEnabled.class)
    public EpicSigningKeys epicSigningKeys(EpicLoginSettings settings) {
        return settings.signingKeys();
    }

    /** Open exactly when the settings exist, which is to say when they passed validation. */
    @Bean
    public EpicReleaseGate epicReleaseGate(Optional<EpicLoginSettings> settings) {
        return new EpicReleaseGate(settings.isPresent());
    }
}
