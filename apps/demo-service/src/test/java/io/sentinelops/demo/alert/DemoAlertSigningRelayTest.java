package io.sentinelops.demo.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class DemoAlertSigningRelayTest {
    private static final String SOURCE = "demo-alertmanager";
    private static final String SECRET = "demo-only-webhook-test-key-over-32-bytes";

    @Test
    void forwardsExactBytesWithFreshTimestampAndNonceSignature() throws Exception {
        var body = "{\"alerts\":[{\"summary\":\"汉字\"}]}".getBytes(StandardCharsets.UTF_8);
        var received = new AtomicReference<byte[]>();
        var source = new AtomicReference<String>();
        var timestamp = new AtomicReference<String>();
        var nonce = new AtomicReference<String>();
        var signature = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            source.set(exchange.getRequestHeaders().getFirst("X-Sentinel-Source"));
            timestamp.set(exchange.getRequestHeaders().getFirst("X-Sentinel-Timestamp"));
            nonce.set(exchange.getRequestHeaders().getFirst("X-Sentinel-Nonce"));
            signature.set(exchange.getRequestHeaders().getFirst("X-Sentinel-Signature"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            var relay = new DemoAlertSigningRelay(target(server), SOURCE, SECRET);
            assertThat(relay.forward(body)).isEqualTo(202);
            assertThat(received.get()).isEqualTo(body);
            assertThat(source.get()).isEqualTo(SOURCE);
            assertThat(Long.parseLong(timestamp.get()))
                    .isBetween(java.time.Instant.now().minusSeconds(5).getEpochSecond(),
                            java.time.Instant.now().plusSeconds(5).getEpochSecond());
            assertThat(nonce.get()).matches("[0-9a-f]{32}");
            assertThat(signature.get()).isEqualTo(sign(timestamp.get(), nonce.get(), body));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downstreamFailureIsReturnedWithoutRelayRetry() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var relay = new DemoAlertSigningRelay(target(server), SOURCE, SECRET);
            assertThat(relay.forward("{}".getBytes(StandardCharsets.UTF_8))).isEqualTo(502);
            assertThat(requests.get()).isOne();
        } finally {
            server.stop(0);
        }
    }

    private String target(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    private String sign(String timestamp, String nonce, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
