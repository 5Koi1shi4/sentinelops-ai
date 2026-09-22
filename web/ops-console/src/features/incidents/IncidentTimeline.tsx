import type { EvidenceReference, IncidentTimelinePage } from "./incidentApi";

export function IncidentTimeline({
    timeline,
    evidence,
}: {
    timeline: IncidentTimelinePage;
    evidence: EvidenceReference[];
}) {
    const evidenceById = new Map(evidence.map((item) => [item.id, item]));
    return (
        <section
            className="control-card timeline-panel"
            aria-labelledby="timeline-title"
        >
            <header className="card-heading compact-heading">
                <div>
                    <p className="eyebrow">IMMUTABLE EVENT LOG</p>
                    <h2 id="timeline-title">事故时间线</h2>
                </div>
                <span className="event-count">
                    {timeline.items.length} EVENTS
                </span>
            </header>
            {timeline.items.length === 0 ? (
                <p className="empty-note">
                    尚无事件。告警接入后会在这里形成可审计记录。
                </p>
            ) : (
                <ol className="timeline-list">
                    {timeline.items.map((item) => {
                        return (
                            <li key={item.id}>
                                <span className="timeline-sequence">
                                    {item.sequence}
                                </span>
                                <div>
                                    <header>
                                        <strong>
                                            {item.eventType.replaceAll(
                                                "_",
                                                " ",
                                            )}
                                        </strong>
                                        <time dateTime={item.occurredAt}>
                                            {new Intl.DateTimeFormat("zh-CN", {
                                                hour: "2-digit",
                                                minute: "2-digit",
                                                second: "2-digit",
                                            }).format(
                                                new Date(item.occurredAt),
                                            )}
                                        </time>
                                    </header>
                                    <p>事件摘要：{item.summary}</p>
                                    <small>
                                        {item.actorType} / {item.actorId}
                                        {` · TRACE ${item.traceId ?? "未提供"}`}
                                    </small>
                                    {item.evidenceIds.length > 0 ? (
                                        <div className="evidence-links">
                                            {item.evidenceIds.map((id) => {
                                                const reference =
                                                    evidenceById.get(id);
                                                return reference ? (
                                                    <a
                                                        key={id}
                                                        href={`#evidence-${id}`}
                                                        aria-label={`事件证据 ${reference.sourceRef}`}
                                                    >
                                                        {reference.sourceRef}
                                                    </a>
                                                ) : null;
                                            })}
                                        </div>
                                    ) : null}
                                </div>
                            </li>
                        );
                    })}
                </ol>
            )}
        </section>
    );
}
