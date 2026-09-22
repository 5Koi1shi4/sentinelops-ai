package io.sentinelops.api.incident.application.evidence;

/** Provider rejected the read because its rate limit was reached. */
public final class EvidenceSourceRateLimited extends EvidenceSourceException {

    public EvidenceSourceRateLimited() {
        super("evidence source rate limited");
    }
}
