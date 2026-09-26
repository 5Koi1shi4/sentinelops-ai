package io.sentinelops.executor.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ProductionAdapterIsolationTest {

    @Test
    void productionContextDoesNotRegisterDemoRunbookAdapter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.register(DemoHttpRunbookAdapter.class);

            assertThat(context.containsBeanDefinition("demoHttpRunbookAdapter")).isFalse();
        }
    }

    @Test
    void demoContextStillRegistersDemoRunbookAdapter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("demo");
            context.register(DemoHttpRunbookAdapter.class);

            assertThat(context.containsBeanDefinition("demoHttpRunbookAdapter")).isTrue();
        }
    }
}
