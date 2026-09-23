package io.sentinelops.demo.alert;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = {"sentinelops.demo-mode", "sentinelops.demo-alert-relay-mode"},
        havingValue = "true")
public final class DemoAlertRelayController {
    private static final int MAX_BODY_BYTES = 1_048_576;

    private final DemoAlertSigningRelay relay;

    public DemoAlertRelayController(DemoAlertSigningRelay relay) {
        this.relay = relay;
    }

    @PostMapping("/internal/demo/alertmanager-relay")
    ResponseEntity<Void> forward(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                .matches("application/json(?:;.*)?")) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
            }
            return ResponseEntity.status(HttpStatusCode.valueOf(relay.forward(body))).build();
        } catch (IOException failure) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
}
