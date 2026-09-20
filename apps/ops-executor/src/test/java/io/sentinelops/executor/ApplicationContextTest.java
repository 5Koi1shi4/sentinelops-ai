package io.sentinelops.executor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "sentinelops.executor.enabled=false",
    "sentinelops.oauth2.token-uri=http://localhost.invalid/oauth/token",
    "sentinelops.oauth2.client-id=test-executor",
    "sentinelops.oauth2.client-secret=test-secret"
})
class ApplicationContextTest {

    @Test
    void applicationContextLoads() {}
}
