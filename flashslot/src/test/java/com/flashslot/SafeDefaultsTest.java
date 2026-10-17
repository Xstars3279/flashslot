package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

class SafeDefaultsTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            // The integration launcher explicitly sets APP_PROFILE=local; model a fresh launch without it.
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            new ConfigDataApplicationContextInitializer().initialize(context);
        });
    }
    @Test void freshLaunchDefaultsToProductionWithoutDevelopmentRegistrationOrDocs() {
        runner().run(context -> {
            var env=context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("production");
            assertThat(env.getProperty("app.registration.development-code-enabled",Boolean.class)).isFalse();
            assertThat(env.getProperty("springdoc.api-docs.enabled",Boolean.class)).isFalse();
            assertThat(env.getProperty("springdoc.swagger-ui.enabled",Boolean.class)).isFalse();
        });
    }
    @Test void explicitLocalProfileStillEnablesDevelopmentTools() {
        runner().withPropertyValues("APP_PROFILE=local").run(context -> {
            var env=context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("local");
            assertThat(env.getProperty("app.registration.development-code-enabled",Boolean.class)).isTrue();
            assertThat(env.getProperty("springdoc.api-docs.enabled",Boolean.class)).isTrue();
        });
    }
}
