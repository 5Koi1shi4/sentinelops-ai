package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentinelops.api.execution.adapter.out.verification.DemoHttpVerificationProbe;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ProductionVerificationIsolationTest {

    @Test
    void productionContextDoesNotRegisterDemoHealthProbe() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.register(DemoHttpVerificationProbe.class);

            assertThat(context.containsBeanDefinition("demoHttpVerificationProbe")).isFalse();
        }
    }

    @Test
    void demoContextCanStillRegisterDemoHealthProbe() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("demo");
            context.register(DemoHttpVerificationProbe.class);

            assertThat(context.containsBeanDefinition("demoHttpVerificationProbe")).isTrue();
        }
    }
}
