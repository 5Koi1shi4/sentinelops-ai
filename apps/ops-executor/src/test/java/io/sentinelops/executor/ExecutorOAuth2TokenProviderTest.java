package io.sentinelops.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ExecutorOAuth2TokenProviderTest {

    @Test
    void cachesClientCredentialsTokenUntilThirtySecondEarlyRefresh(
            CapturedOutput output) {
        var clock = new MutableClock(Instant.parse("2026-09-20T10:00:00Z"));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://issuer.example.test/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(containsString("client_id=executor-client")))
                .andExpect(content().string(containsString("client_secret=super-secret")))
                .andExpect(content().string(containsString("audience=sentinelops-api")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-one\",\"expires_in\":120}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://issuer.example.test/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-two\",\"expires_in\":120}",
                        MediaType.APPLICATION_JSON));
        var provider = new ExecutorOAuth2TokenProvider(
                builder.build(),
                "https://issuer.example.test/token",
                "executor-client",
                "super-secret",
                clock);

        assertThat(provider.accessToken("sentinelops-api", "")).isEqualTo("token-one");
        clock.advanceSeconds(89);
        assertThat(provider.accessToken("sentinelops-api", "")).isEqualTo("token-one");
        clock.advanceSeconds(2);
        assertThat(provider.accessToken("sentinelops-api", "")).isEqualTo("token-two");

        server.verify();
        assertThat(output).doesNotContain("super-secret", "token-one", "token-two");
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
