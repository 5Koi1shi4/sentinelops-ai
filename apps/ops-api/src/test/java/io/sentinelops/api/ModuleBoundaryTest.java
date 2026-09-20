package io.sentinelops.api;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {

    @Test
    void modulesAreAcyclic() {
        ApplicationModules.of(SentinelOpsApiApplication.class).verify();
    }
}
