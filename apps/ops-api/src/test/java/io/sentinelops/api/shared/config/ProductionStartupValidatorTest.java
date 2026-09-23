package io.sentinelops.api.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import io.sentinelops.api.execution.adapter.out.ticket.DemoEphemeralKeyConfiguration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ProductionStartupValidatorTest {
    @Test
    void acceptsExplicitSafeProductionSettings() {
        assertThatCode(() -> ProductionStartupValidator.validate(safeSettings()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeSettingsBeforeProductionCanServeTraffic() {
        List<Consumer<MockEnvironment>> unsafe = List.of(
                env -> env.setActiveProfiles("production", "demo"),
                env -> env.setActiveProfiles("production", "test"),
                env -> env.setProperty("sentinelops.demo-mode", "true"),
                env -> env.setProperty("sentinelops.demo.users", "operator"),
                env -> env.setProperty("sentinelops.ai.provider", "deterministic"),
                env -> env.setProperty("sentinelops.security.allowed-origins", "*"),
                env -> env.setProperty("sentinelops.security.allowed-origins", "http://app.example.test"),
                env -> env.setProperty("sentinelops.security.issuer", "http://idp.example.test/realms/prod"),
                env -> env.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "http://idp.example.test/realms/prod"),
                env -> env.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "https://other.example.test/realms/prod"),
                env -> env.setProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        "http://idp.example.test/keys"),
                env -> env.setProperty("spring.datasource.password", "sentinelops"),
                env -> env.setProperty("sentinelops.execution-ticket.private-jwk", "inline-private-key"),
                env -> env.setProperty("sentinelops.execution-ticket.private-jwk-secret-ref", ""),
                env -> env.setProperty("SENTINELOPS_TICKET_SIGNING_JWK", ""));
        for (var mutation : unsafe) {
            var env = safeSettings();
            mutation.accept(env);
            assertThatThrownBy(() -> ProductionStartupValidator.validate(env))
                    .as("unsafe production setting %s", unsafe.indexOf(mutation))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void resolvesOnlyNamedEnvironmentSecretReferences() {
        var env = safeSettings();
        assertThat(SecretReference.resolve(env,
                "env:SENTINELOPS_TICKET_SIGNING_JWK", "ticket key"))
                .isEqualTo("test-private-jwk");
        assertThatThrownBy(() -> SecretReference.resolve(env, "raw-secret", "ticket key"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretReference.resolve(env,
                "env:MISSING_TICKET_KEY", "ticket key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionProfileRunsValidationDuringContextStartup() {
        var safe = safeSettings();
        safe.setActiveProfiles("production");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(safe);
            context.register(ProductionStartupValidator.class);
            context.refresh();
            assertThat(context.getBean(ProductionStartupValidator.class)).isNotNull();
        }

        var unsafe = safeSettings();
        unsafe.setActiveProfiles("production");
        unsafe.setProperty("sentinelops.ai.provider", "deterministic");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(unsafe);
            context.register(ProductionStartupValidator.class);
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void productionWithTestProfileNeverRegistersAnEphemeralSigningKey() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production", "test");
            context.register(DemoEphemeralKeyConfiguration.class);
            context.refresh();
            assertThat(context.getBeanNamesForType(RSAKey.class)).isEmpty();
        }
    }

    private MockEnvironment safeSettings() {
        return new MockEnvironment()
                .withProperty("sentinelops.ai.provider", "manual-only")
                .withProperty("sentinelops.security.allowed-origins", "https://app.example.test")
                .withProperty("sentinelops.security.issuer", "https://idp.example.test/realms/prod")
                .withProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        "https://idp.example.test/keys")
                .withProperty("spring.datasource.password", "unique-random-secret")
                .withProperty("sentinelops.execution-ticket.private-jwk-secret-ref",
                        "env:SENTINELOPS_TICKET_SIGNING_JWK")
                .withProperty("SENTINELOPS_TICKET_SIGNING_JWK", "test-private-jwk");
    }
}
