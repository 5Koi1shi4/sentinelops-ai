package io.sentinelops.api.incident.adapter.in.webhook;

import io.sentinelops.api.shared.problem.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads the exact signed bytes before JSON binding, with a fixed allocation ceiling. */
@Component
public final class BoundedWebhookRequest {
    private static final int MAX_DEPTH = 64;

    public byte[] read(HttpServletRequest request) {
        if (request.getContentLengthLong() > WebhookSecurityProperties.MAX_BODY_BYTES) {
            throw tooLarge();
        }
        try {
            var body = request.getInputStream()
                    .readNBytes(WebhookSecurityProperties.MAX_BODY_BYTES + 1);
            if (body.length > WebhookSecurityProperties.MAX_BODY_BYTES) {
                throw tooLarge();
            }
            return body;
        } catch (IOException failure) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST,
                    "WEBHOOK_BODY_INVALID", "Webhook body could not be read.");
        }
    }

    public JsonNode parse(byte[] body, String contentType, ObjectMapper mapper) {
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                .matches("application/json(?:;.*)?")) {
            throw new ApiProblemException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "WEBHOOK_MEDIA_TYPE", "Webhook requires JSON content type.");
        }
        validateUtf8AndDepth(body);
        try {
            var payload = mapper.readTree(body);
            if (payload == null || !payload.isObject()) {
                throw invalid();
            }
            return payload;
        } catch (JacksonException failure) {
            throw invalid();
        }
    }

    private void validateUtf8AndDepth(byte[] body) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
        } catch (CharacterCodingException failure) {
            throw invalid();
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (byte value : body) {
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    quoted = false;
                }
            } else if (value == '"') {
                quoted = true;
            } else if (value == '{' || value == '[') {
                if (++depth > MAX_DEPTH) throw invalid();
            } else if (value == '}' || value == ']') {
                if (--depth < 0) throw invalid();
            }
        }
        if (quoted || depth != 0) throw invalid();
    }

    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST,
                "WEBHOOK_BODY_INVALID", "Webhook JSON is invalid or exceeds nesting limits.");
    }

    private ApiProblemException tooLarge() {
        return new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE,
                "WEBHOOK_BODY_TOO_LARGE", "Webhook body exceeds one MiB.");
    }
}
