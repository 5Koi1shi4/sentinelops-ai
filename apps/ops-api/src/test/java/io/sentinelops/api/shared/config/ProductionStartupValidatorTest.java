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
                env -> env.setProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/sentinelops"),
                env -> env.setProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/sentinelops?sslmode=require"),
                env -> env.setProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/sentinelops?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory"),
                env -> env.setProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/sentinelops?sslmode=verify-full&ssl%66actory=org.postgresql.ssl.NonValidatingFactory"),
                env -> env.setProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/sentinelops?sslmode=verify-full&sslhostnameverifier=example.AcceptAll"),
                env -> env.setProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/sentinelops?sslmode=verify-full&user=inline&password=inline"),
                env -> env.setProperty("spring.data.redis.ssl.enabled", "false"),
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

    @Test
    void allowsPlaintextOnlyForTheExplicitLocalDataServiceAliases() {
        var local = safeSettings()
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/sentinelops")
                .withProperty("spring.data.redis.host", "valkey")
                .withProperty("spring.data.redis.ssl.enabled", "false");
        assertThatCode(() -> ProductionStartupValidator.validate(local))
                .doesNotThrowAnyException();

        local.setProperty("spring.data.redis.host", "valkey.example.test");
        assertThatThrownBy(() -> ProductionStartupValidator.validate(local))
                .isInstanceOf(IllegalStateException.class);

        var inlineCredentials = safeSettings()
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://postgres:5432/sentinelops?user=inline&password=inline")
                .withProperty("spring.data.redis.host", "valkey")
                .withProperty("spring.data.redis.ssl.enabled", "false");
        assertThatThrownBy(() -> ProductionStartupValidator.validate(inlineCredentials))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsTheDocumentedJavaTrustStoreSslFactory() {
        var settings = safeSettings().withProperty("spring.datasource.url",
                "jdbc:postgresql://db.example.test:5432/sentinelops?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory");
        assertThatCode(() -> ProductionStartupValidator.validate(settings))
                .doesNotThrowAnyException();
    }

    private MockEnvironment safeSettings() {
        return new MockEnvironment()
                .withProperty("sentinelops.ai.provider", "manual-only")
                .withProperty("sentinelops.security.allowed-origins", "https://app.example.test")
                .withProperty("sentinelops.security.issuer", "https://idp.example.test/realms/prod")
                .withProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        "https://idp.example.test/keys")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/sentinelops?sslmode=verify-full")
                .withProperty("spring.datasource.password", "unique-random-secret")
                .withProperty("spring.data.redis.host", "valkey.example.test")
                .withProperty("spring.data.redis.ssl.enabled", "true")
                .withProperty("sentinelops.execution-ticket.private-jwk-secret-ref",
                        "env:SENTINELOPS_TICKET_SIGNING_JWK")
                .withProperty("SENTINELOPS_TICKET_SIGNING_JWK", "test-private-jwk");
    }
}
