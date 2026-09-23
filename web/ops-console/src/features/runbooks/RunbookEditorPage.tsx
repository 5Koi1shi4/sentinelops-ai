import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { ApiProblem } from "../../api/client";
import { useAuth } from "../../auth/authContext";
import { useServices } from "./runbookApi";
import {
    idempotencyKey,
    useCreateRunbookDraft,
    usePublishRunbookVersion,
    useReviewRunbookVersion,
    useRunbookDiff,
    useRunbookVersion,
    useRunbookVersions,
    useUpdateRunbookDraft,
    type DraftContent,
    type DraftInput,
    type RunbookDefinition,
    type ServiceSummary,
    type VersionDiff,
    type VersionView,
} from "./runbookApi";
import { RunbookVersionDiff } from "./RunbookVersionDiff";
import "./runbooks.css";

interface FormValues {
    runbookKey: string;
    displayName: string;
    ownerTeam: string;
    serviceId: string;
    attempts: string;
    intervalSeconds: string;
    markdown: string;
}

type PendingCommand =
    | {
          kind: "create";
          runbookKey: string;
          body: DraftInput;
          key: string;
      }
    | {
          kind: "update";
          runbookKey: string;
          body: DraftContent;
          revision: number;
          key: string;
      }
    | { kind: "review"; runbookKey: string; revision: number; key: string }
    | { kind: "publish"; runbookKey: string; revision: number; key: string };

const emptyForm: FormValues = {
    runbookKey: "",
    displayName: "",
    ownerTeam: "",
    serviceId: "",
    attempts: "3",
    intervalSeconds: "2",
    markdown: "",
};

function versionToForm(version: VersionView): FormValues {
    return {
        runbookKey: version.runbookKey,
        displayName: version.displayName,
        ownerTeam: version.ownerTeam,
        serviceId: version.serviceId,
        attempts: String(version.definition.verification.attempts),
        intervalSeconds: String(
            version.definition.verification.intervalSeconds,
        ),
        markdown: version.markdown,
    };
}

function definitionFromForm(form: FormValues): RunbookDefinition {
    return {
        runbookKey: form.runbookKey.trim(),
        risk: "R1",
        adapterId: "demo-http",
        parameters: {
            type: "object",
            properties: {
                replicas: { type: "integer", minimum: 1, maximum: 1 },
            },
            required: ["replicas"],
            additionalProperties: false,
        },
        steps: [
            { stepId: "recover-one", operation: "recover_connection_pool" },
        ],
        verification: {
            probe: "demo_checkout_health",
            successThreshold: 1,
            attempts: Number(form.attempts),
            intervalSeconds: Number(form.intervalSeconds),
        },
        rollback: null,
    };
}

function draftInputFromForm(form: FormValues): DraftInput {
    return {
        serviceId: form.serviceId,
        displayName: form.displayName.trim(),
        ownerTeam: form.ownerTeam.trim(),
        definition: definitionFromForm(form),
        markdown: form.markdown,
    };
}

function draftContentFromForm(form: FormValues): DraftContent {
    return {
        definition: definitionFromForm(form),
        markdown: form.markdown,
    };
}

function sameForm(left: FormValues, right: FormValues) {
    return JSON.stringify(left) === JSON.stringify(right);
}

function canUseService(
    userServiceIds: string[],
    isPlatformAdmin: boolean,
    id: string,
) {
    return isPlatformAdmin || userServiceIds.includes(id);
}

function serviceName(service?: ServiceSummary, fallback = "") {
    return service
        ? `${service.serviceKey} · ${service.displayName}`
        : fallback;
}

function errorMessage(error: unknown) {
    if (error instanceof ApiProblem) {
        return error.message;
    }
    return error instanceof Error ? error.message : "请求失败，请稍后重试。";
}

function isPreconditionFailure(error: unknown) {
    return error instanceof ApiProblem && error.status === 412;
}

function isDefiniteValidationRejection(error: unknown) {
    if (
        !(error instanceof ApiProblem) ||
        (error.status !== 400 && error.status !== 422)
    ) {
        return false;
    }
    return !String(error.problem.errorCode ?? "").startsWith("IDEMPOTENCY_");
}

export function RunbookEditorPage() {
    const { key: routeKey, id: routeVersionId } = useParams();
    const editorIdentity = `${routeKey ?? ""}:${routeVersionId ?? "new"}`;
    return <RunbookEditorRoute key={editorIdentity} />;
}

function RunbookEditorRoute() {
    const { key: routeKey, id: routeVersionId } = useParams();
    const navigate = useNavigate();
    const { user } = useAuth();
    const isNew = !routeVersionId;
    const isPlatformAdmin = Boolean(user?.roles.includes("PLATFORM_ADMIN"));
    const hasAuthorRole = Boolean(
        user?.roles.some(
            (role) => role === "RUNBOOK_ADMIN" || role === "PLATFORM_ADMIN",
        ),
    );
    const servicesQuery = useServices(
        Boolean(user && (!isNew || hasAuthorRole)),
    );
    const versionQuery = useRunbookVersion(routeVersionId ?? "");
    const versionsQuery = useRunbookVersions(routeKey ?? "");
    const formIdentity = routeVersionId ?? "new";
    const [storedForm, setStoredForm] = useState<{
        identity: string | null;
        value: FormValues;
    }>(() => ({ identity: null, value: emptyForm }));
    const version = versionQuery.data;
    const form =
        storedForm.identity === formIdentity
            ? storedForm.value
            : version
              ? versionToForm(version)
              : emptyForm;
    const [mutationError, setMutationError] = useState<unknown | null>(null);
    const [mutationStatus, setMutationStatus] = useState("");
    const [pendingCommand, setPendingCommand] = useState<PendingCommand | null>(
        null,
    );
    const [conflict, setConflict] = useState(false);
    const [conflictRefreshed, setConflictRefreshed] = useState(false);
    const [showPublishConfirmation, setShowPublishConfirmation] =
        useState(false);
    const publishTriggerRef = useRef<HTMLButtonElement>(null);
    const publishConfirmRef = useRef<HTMLButtonElement>(null);
    const activeRef = useRef(true);

    useEffect(() => {
        activeRef.current = true;
        return () => {
            activeRef.current = false;
        };
    }, []);

    const runbookKey = routeKey ?? form.runbookKey.trim();
    const createMutation = useCreateRunbookDraft(runbookKey);
    const updateMutation = useUpdateRunbookDraft(
        routeKey ?? "",
        routeVersionId ?? "",
    );
    const reviewMutation = useReviewRunbookVersion(
        routeKey ?? "",
        routeVersionId ?? "",
    );
    const publishMutation = usePublishRunbookVersion(
        routeKey ?? "",
        routeVersionId ?? "",
    );
    const versionList = versionsQuery.data?.pages.flat() ?? [];
    const serviceList = servicesQuery.data?.pages.flat() ?? [];
    const {
        dataUpdatedAt: servicesUpdatedAt,
        fetchNextPage: fetchNextServicePage,
        hasNextPage: hasMoreServices,
        isFetchingNextPage: isFetchingMoreServices,
    } = servicesQuery;
    const allowedServices = serviceList.filter((service) =>
        canUseService(user?.serviceIds ?? [], isPlatformAdmin, service.id),
    );
    const currentService = serviceList.find(
        (service) => service.id === version?.serviceId,
    );
    const precedingPublished = version
        ? versionList
              .filter(
                  (candidate) =>
                      candidate.lifecycle === "published" &&
                      candidate.versionNumber < version.versionNumber,
              )
              .sort(
                  (left, right) => right.versionNumber - left.versionNumber,
              )[0]
        : undefined;
    const currentVersionInHistory = Boolean(
        version &&
        versionList.some(
            (candidate) => candidate.versionNumber >= version.versionNumber,
        ),
    );
    const versionHistoryThroughCurrent = Boolean(
        version && versionsQuery.isSuccess && currentVersionInHistory,
    );
    const versionHistoryMissingCurrent = Boolean(
        version &&
        versionsQuery.isSuccess &&
        !versionsQuery.hasNextPage &&
        !currentVersionInHistory,
    );
    const baselineIsNew = Boolean(
        version && versionHistoryThroughCurrent && !precedingPublished,
    );
    const diffQuery = useRunbookDiff(
        precedingPublished?.id ?? "",
        version?.id ?? "",
        hasAuthorRole && versionHistoryThroughCurrent,
    );
    const currentDefinition = version?.definition ?? definitionFromForm(form);
    const diff: VersionDiff | undefined = diffQuery.data;

    useEffect(() => {
        if (
            version &&
            !currentService &&
            hasMoreServices &&
            !isFetchingMoreServices
        ) {
            void fetchNextServicePage();
        }
    }, [
        currentService,
        fetchNextServicePage,
        hasMoreServices,
        isFetchingMoreServices,
        servicesUpdatedAt,
        version,
    ]);

    useEffect(() => {
        if (showPublishConfirmation) {
            publishConfirmRef.current?.focus();
        }
    }, [showPublishConfirmation]);

    const fieldsChanged = Boolean(
        version && !sameForm(form, versionToForm(version)),
    );
    const versionIsDraft = version?.lifecycle === "draft";
    const canManageVersion = Boolean(
        hasAuthorRole &&
        version &&
        canUseService(
            user?.serviceIds ?? [],
            isPlatformAdmin,
            version.serviceId,
        ),
    );
    const canEdit = Boolean(
        isNew ? hasAuthorRole : canManageVersion && versionIsDraft,
    );
    const isMutationPending =
        createMutation.isPending ||
        updateMutation.isPending ||
        reviewMutation.isPending ||
        publishMutation.isPending;
    const canChangeForm = canEdit && !isMutationPending && !pendingCommand;
    const numericAttempts = Number(form.attempts);
    const numericInterval = Number(form.intervalSeconds);
    const isFormValid = Boolean(
        /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(form.runbookKey.trim()) &&
        form.displayName.trim() &&
        form.ownerTeam.trim() &&
        form.serviceId &&
        form.markdown.trim() &&
        Number.isInteger(numericAttempts) &&
        numericAttempts >= 1 &&
        numericAttempts <= 20 &&
        Number.isInteger(numericInterval) &&
        numericInterval >= 1 &&
        numericInterval <= 60,
    );
    const diffReady = Boolean(
        isNew ||
        (version &&
            hasAuthorRole &&
            versionHistoryThroughCurrent &&
            (baselineIsNew || Boolean(diff)) &&
            !versionsQuery.isError &&
            !diffQuery.isError),
    );
    const canReview = Boolean(
        canManageVersion &&
        versionIsDraft &&
        version?.canReview &&
        !version.reviewerPrincipalId,
    );
    const canPublish = Boolean(
        canManageVersion &&
        versionIsDraft &&
        version?.reviewerPrincipalId &&
        !fieldsChanged &&
        !conflict &&
        !pendingCommand &&
        diffReady,
    );

    function updateForm<K extends keyof FormValues>(
        key: K,
        value: FormValues[K],
    ) {
        setStoredForm({
            identity: formIdentity,
            value: { ...form, [key]: value },
        });
        setPendingCommand(null);
        setMutationError(null);
        setMutationStatus("");
        setShowPublishConfirmation(false);
    }

    async function executeCommand(command: PendingCommand) {
        setMutationError(null);
        setMutationStatus("");
        setPendingCommand(command);
        try {
            if (command.kind === "create") {
                const created = await createMutation.mutateAsync({
                    body: command.body,
                    idempotencyKey: command.key,
                });
                if (!activeRef.current) {
                    return;
                }
                setPendingCommand(null);
                setMutationStatus("草稿已创建");
                navigate(
                    `/runbooks/${encodeURIComponent(created.runbookKey)}/versions/${created.id}`,
                );
                return;
            }
            if (command.kind === "update") {
                await updateMutation.mutateAsync({
                    body: command.body,
                    revision: command.revision,
                    idempotencyKey: command.key,
                });
                if (!activeRef.current) {
                    return;
                }
                setPendingCommand(null);
                setMutationStatus("草稿已保存");
                return;
            }
            if (command.kind === "review") {
                await reviewMutation.mutateAsync({
                    revision: command.revision,
                    idempotencyKey: command.key,
                });
                if (!activeRef.current) {
                    return;
                }
                setPendingCommand(null);
                setMutationStatus("独立评审已确认");
                return;
            }
            await publishMutation.mutateAsync({
                revision: command.revision,
                idempotencyKey: command.key,
            });
            if (!activeRef.current) {
                return;
            }
            setPendingCommand(null);
            setMutationStatus("Runbook 版本已发布并冻结");
            setShowPublishConfirmation(false);
        } catch (error) {
            if (!activeRef.current) {
                return;
            }
            setMutationError(error);
            if (isPreconditionFailure(error)) {
                setConflict(true);
                setConflictRefreshed(false);
                setPendingCommand(null);
            } else if (isDefiniteValidationRejection(error)) {
                setPendingCommand(null);
            }
        }
    }

    function onSubmit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (
            !canEdit ||
            !isFormValid ||
            isMutationPending ||
            Boolean(pendingCommand) ||
            conflict
        ) {
            return;
        }
        if (isNew) {
            const body = draftInputFromForm(form);
            void executeCommand({
                kind: "create",
                runbookKey: form.runbookKey.trim(),
                body,
                key: idempotencyKey("runbook-create"),
            });
            return;
        }
        if (!version || !fieldsChanged) {
            return;
        }
        void executeCommand({
            kind: "update",
            runbookKey: version.runbookKey,
            body: draftContentFromForm(form),
            revision: version.revision,
            key: idempotencyKey("runbook-update"),
        });
    }

    async function createNextDraft() {
        if (
            !version ||
            !canManageVersion ||
            version.lifecycle !== "published" ||
            pendingCommand
        ) {
            return;
        }
        const body: DraftInput = {
            serviceId: version.serviceId,
            displayName: version.displayName,
            ownerTeam: version.ownerTeam,
            definition: version.definition,
            markdown: version.markdown,
        };
        await executeCommand({
            kind: "create",
            runbookKey: version.runbookKey,
            body,
            key: idempotencyKey("runbook-create"),
        });
    }

    function confirmIndependentReview() {
        if (
            !version ||
            !canReview ||
            fieldsChanged ||
            conflict ||
            isMutationPending ||
            pendingCommand
        ) {
            return;
        }
        void executeCommand({
            kind: "review",
            runbookKey: version.runbookKey,
            revision: version.revision,
            key: idempotencyKey("runbook-review"),
        });
    }

    function confirmPublish() {
        if (!version || !canPublish || isMutationPending || pendingCommand) {
            return;
        }
        void executeCommand({
            kind: "publish",
            runbookKey: version.runbookKey,
            revision: version.revision,
            key: idempotencyKey("runbook-publish"),
        });
    }

    if (!user) {
        return (
            <main className="runbook-page" id="main-content">
                <div className="runbook-state is-error" role="alert">
                    请登录后查看 Runbook。
                </div>
            </main>
        );
    }

    if (isNew && !hasAuthorRole) {
        return (
            <main className="runbook-page" id="main-content">
                <div className="runbook-state is-error" role="alert">
                    只有 Runbook 管理员可以创建草稿。
                </div>
            </main>
        );
    }

    if (!isNew && versionQuery.isPending) {
        return (
            <main className="runbook-page" id="main-content">
                <div className="runbook-state" role="status">
                    正在读取 Runbook 版本…
                </div>
            </main>
        );
    }

    if (!isNew && versionQuery.isError) {
        const denied =
            versionQuery.error instanceof ApiProblem &&
            versionQuery.error.status === 403;
        return (
            <main className="runbook-page" id="main-content">
                <div className="runbook-state is-error" role="alert">
                    <strong>
                        {denied
                            ? "无权读取此 Runbook 版本"
                            : "无法读取 Runbook 版本"}
                    </strong>
                    <span>{errorMessage(versionQuery.error)}</span>
                    <Link className="secondary-action" to="/runbooks">
                        返回 Runbook 列表
                    </Link>
                </div>
            </main>
        );
    }

    if (!isNew && !version) {
        return (
            <main className="runbook-page" id="main-content">
                <div className="runbook-state is-error" role="alert">
                    找不到这个 Runbook 版本。
                </div>
            </main>
        );
    }

    if (
        !isNew &&
        version &&
        !hasAuthorRole &&
        version.lifecycle !== "published"
    ) {
        return (
            <main className="runbook-page" id="main-content">
                <div className="runbook-state is-error" role="alert">
                    你只能查看已发布的 Runbook 版本。
                </div>
            </main>
        );
    }

    const resolvedServiceLabel = version
        ? serviceName(currentService, version.serviceId)
        : serviceName(
              allowedServices.find((service) => service.id === form.serviceId),
              "请选择服务",
          );

    return (
        <main className="runbook-page" id="main-content">
            <nav className="runbook-breadcrumb" aria-label="面包屑导航">
                <Link to="/runbooks">Runbook 管理</Link>
                <span aria-hidden="true">/</span>
                <span>{isNew ? "创建草稿" : version?.runbookKey}</span>
            </nav>
            <header className="runbook-page-heading runbook-editor-heading">
                <div>
                    <p className="eyebrow">
                        {isNew
                            ? "NEW CONTROLLED PROCEDURE"
                            : "IMMUTABLE VERSION LINEAGE"}
                    </p>
                    <h1>
                        {isNew ? "创建 Runbook 草稿" : version?.displayName}
                    </h1>
                    <p className="runbook-lede">
                        {isNew
                            ? "登记责任范围，再编写受控的连接池恢复步骤。"
                            : "版本内容与服务身份绑定；已发布版本保持只读。"}
                    </p>
                </div>
                {!isNew && version ? (
                    <div className="runbook-version-lockup">
                        <span
                            className={`runbook-lifecycle state-${version.lifecycle}`}
                        >
                            {version.lifecycle === "published"
                                ? "已发布"
                                : version.lifecycle === "retired"
                                  ? "已退役"
                                  : "草稿"}
                        </span>
                        <strong>
                            {version.lifecycle === "draft"
                                ? "草稿版本"
                                : "版本"}{" "}
                            {version.versionNumber}
                        </strong>
                        <small>修订 {version.revision}</small>
                    </div>
                ) : null}
            </header>

            {!isNew && version && !canManageVersion && hasAuthorRole ? (
                <p className="runbook-notice" role="status">
                    当前版本超出你的服务管理范围；你可以查看已发布信息，不能创建或修改版本。
                </p>
            ) : null}

            <div className="runbook-editor-layout">
                <div className="runbook-editor-main">
                    <section
                        className="runbook-panel"
                        aria-labelledby="runbook-identity-title"
                    >
                        <header className="runbook-panel-heading">
                            <div>
                                <p className="eyebrow">
                                    OWNERSHIP / SERVICE SCOPE
                                </p>
                                <h2 id="runbook-identity-title">
                                    责任与服务身份
                                </h2>
                            </div>
                            <span className="runbook-fixed-label">
                                {isNew ? "新建时固定" : "版本身份固定"}
                            </span>
                        </header>
                        <div className="runbook-form-grid">
                            <label
                                className="runbook-field"
                                htmlFor="runbook-key"
                            >
                                <span>Runbook Key</span>
                                <input
                                    aria-describedby="runbook-key-help"
                                    aria-label="Runbook Key"
                                    id="runbook-key"
                                    autoComplete="off"
                                    maxLength={128}
                                    pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
                                    readOnly={!canChangeForm}
                                    required
                                    value={form.runbookKey}
                                    onChange={(event) =>
                                        updateForm(
                                            "runbookKey",
                                            event.target.value,
                                        )
                                    }
                                />
                                <small id="runbook-key-help">
                                    唯一标识；仅允许字母、数字、点、下划线和短横线。
                                </small>
                            </label>
                            <label
                                className="runbook-field"
                                htmlFor="runbook-display-name"
                            >
                                <span>人类可读名称</span>
                                <input
                                    id="runbook-display-name"
                                    maxLength={200}
                                    readOnly={!canChangeForm}
                                    required
                                    value={form.displayName}
                                    onChange={(event) =>
                                        updateForm(
                                            "displayName",
                                            event.target.value,
                                        )
                                    }
                                />
                            </label>
                            <label
                                className="runbook-field"
                                htmlFor="runbook-owner-team"
                            >
                                <span>责任团队</span>
                                <input
                                    id="runbook-owner-team"
                                    maxLength={128}
                                    readOnly={!canChangeForm}
                                    required
                                    value={form.ownerTeam}
                                    onChange={(event) =>
                                        updateForm(
                                            "ownerTeam",
                                            event.target.value,
                                        )
                                    }
                                />
                            </label>
                            <label
                                className="runbook-field"
                                htmlFor="runbook-service"
                            >
                                <span>所属服务</span>
                                {isNew ? (
                                    <select
                                        id="runbook-service"
                                        disabled={!canChangeForm}
                                        required
                                        value={form.serviceId}
                                        onChange={(event) =>
                                            updateForm(
                                                "serviceId",
                                                event.target.value,
                                            )
                                        }
                                    >
                                        <option value="">选择服务</option>
                                        {allowedServices.map((service) => (
                                            <option
                                                key={service.id}
                                                value={service.id}
                                            >
                                                {service.serviceKey} ·{" "}
                                                {service.displayName}
                                            </option>
                                        ))}
                                    </select>
                                ) : (
                                    <input
                                        id="runbook-service"
                                        readOnly
                                        value={resolvedServiceLabel}
                                    />
                                )}
                            </label>
                            {isNew && servicesQuery.hasNextPage ? (
                                <button
                                    className="runbook-inline-button"
                                    type="button"
                                    disabled={
                                        servicesQuery.isFetchingNextPage ||
                                        !canChangeForm
                                    }
                                    onClick={() =>
                                        void servicesQuery.fetchNextPage()
                                    }
                                >
                                    {servicesQuery.isFetchingNextPage
                                        ? "正在读取服务…"
                                        : "加载更多服务"}
                                </button>
                            ) : null}
                            {!isNew && currentService ? (
                                <p className="runbook-service-owner">
                                    服务责任团队：
                                    <strong>{currentService.ownerTeam}</strong>
                                </p>
                            ) : null}
                            {isNew && servicesQuery.isError ? (
                                <p className="runbook-field-error" role="alert">
                                    无法读取服务列表：
                                    {errorMessage(servicesQuery.error)}
                                </p>
                            ) : null}
                            {isNew &&
                            allowedServices.length === 0 &&
                            !servicesQuery.isPending ? (
                                <p
                                    className="runbook-field-error"
                                    role="status"
                                >
                                    当前服务范围没有可用服务，请联系平台管理员配置权限。
                                </p>
                            ) : null}
                        </div>
                    </section>

                    <form
                        className="runbook-panel runbook-form"
                        onSubmit={onSubmit}
                    >
                        <header className="runbook-panel-heading">
                            <div>
                                <p className="eyebrow">ALLOWLISTED EXECUTION</p>
                                <h2>受控操作与验证参数</h2>
                            </div>
                            <span className="runbook-risk-chip risk-r1">
                                R1
                            </span>
                        </header>
                        <fieldset className="runbook-fixed-fields" disabled>
                            <legend>固定执行边界</legend>
                            <div className="runbook-form-grid">
                                <label className="runbook-field">
                                    <span>风险等级</span>
                                    <select disabled value="R1">
                                        <option value="R1">R1 · 低风险</option>
                                    </select>
                                </label>
                                <label className="runbook-field">
                                    <span>Adapter</span>
                                    <select disabled value="demo-http">
                                        <option value="demo-http">
                                            demo-http
                                        </option>
                                    </select>
                                </label>
                                <label className="runbook-field">
                                    <span>操作</span>
                                    <select
                                        disabled
                                        value="recover_connection_pool"
                                    >
                                        <option value="recover_connection_pool">
                                            recover_connection_pool
                                        </option>
                                    </select>
                                </label>
                                <label className="runbook-field">
                                    <span>副本数</span>
                                    <input value="1" readOnly />
                                    <small>固定为 1；参数范围 1–1。</small>
                                </label>
                                <label className="runbook-field">
                                    <span>健康探针</span>
                                    <select
                                        disabled
                                        value="demo_checkout_health"
                                    >
                                        <option value="demo_checkout_health">
                                            demo_checkout_health
                                        </option>
                                    </select>
                                </label>
                                <label className="runbook-field">
                                    <span>成功阈值</span>
                                    <input value="1 次连续成功" readOnly />
                                </label>
                                <label className="runbook-field">
                                    <span>回滚</span>
                                    <input value="无（固定）" readOnly />
                                </label>
                            </div>
                        </fieldset>

                        <fieldset
                            className="runbook-editable-fields"
                            disabled={!canChangeForm}
                        >
                            <legend>验证时间范围</legend>
                            <div className="runbook-form-grid runbook-form-grid-compact">
                                <label className="runbook-field">
                                    <span>验证次数</span>
                                    <input
                                        aria-label="验证次数"
                                        max={20}
                                        min={1}
                                        required
                                        step={1}
                                        type="number"
                                        value={form.attempts}
                                        onChange={(event) =>
                                            updateForm(
                                                "attempts",
                                                event.target.value,
                                            )
                                        }
                                    />
                                    <small>允许 1–20 次</small>
                                </label>
                                <label className="runbook-field">
                                    <span>验证间隔（秒）</span>
                                    <input
                                        aria-label="验证间隔（秒）"
                                        max={60}
                                        min={1}
                                        required
                                        step={1}
                                        type="number"
                                        value={form.intervalSeconds}
                                        onChange={(event) =>
                                            updateForm(
                                                "intervalSeconds",
                                                event.target.value,
                                            )
                                        }
                                    />
                                    <small>允许 1–60 秒</small>
                                </label>
                            </div>
                        </fieldset>

                        <label
                            className="runbook-field runbook-markdown-field"
                            htmlFor="runbook-markdown"
                        >
                            <span>操作说明（Markdown）</span>
                            <textarea
                                aria-describedby="runbook-markdown-help"
                                aria-label="操作说明（Markdown）"
                                id="runbook-markdown"
                                maxLength={120000}
                                minLength={1}
                                readOnly={!canChangeForm}
                                required
                                rows={9}
                                value={form.markdown}
                                onChange={(event) =>
                                    updateForm("markdown", event.target.value)
                                }
                            />
                            <small id="runbook-markdown-help">
                                仅记录操作员可读说明；执行范围由上方固定的受控操作决定。
                            </small>
                        </label>

                        <details className="runbook-json-preview">
                            <summary>高级：只读 JSON 预览</summary>
                            <pre>
                                {JSON.stringify(
                                    {
                                        ...(version ?? {}),
                                        runbookKey: form.runbookKey,
                                        serviceId: form.serviceId,
                                        displayName: form.displayName,
                                        ownerTeam: form.ownerTeam,
                                        definition: definitionFromForm(form),
                                        markdown: form.markdown,
                                    },
                                    null,
                                    2,
                                )}
                            </pre>
                        </details>

                        {canEdit ? (
                            <div className="runbook-form-actions">
                                <p className="runbook-action-hint">
                                    {isNew
                                        ? "保存后会生成一个待评审草稿版本。"
                                        : fieldsChanged
                                          ? "有未保存更改；评审与发布暂不可用。"
                                          : "已保存内容可由独立评审人确认。"}
                                </p>
                                <button
                                    className="primary-action"
                                    type="submit"
                                    disabled={
                                        !isFormValid ||
                                        isMutationPending ||
                                        Boolean(pendingCommand) ||
                                        conflict ||
                                        (!isNew && !fieldsChanged)
                                    }
                                >
                                    {isNew ? "创建 Runbook 草稿" : "保存草稿"}
                                </button>
                            </div>
                        ) : null}
                    </form>

                    {hasAuthorRole &&
                    (isNew || versionHistoryThroughCurrent) ? (
                        <RunbookVersionDiff
                            diff={diff}
                            currentDefinition={currentDefinition}
                            baselineDefinition={
                                precedingPublished?.definition ?? null
                            }
                            serviceLabel={resolvedServiceLabel}
                            initialBaseline={isNew || baselineIsNew}
                        />
                    ) : null}
                    {!isNew &&
                    version &&
                    hasAuthorRole &&
                    !versionHistoryThroughCurrent ? (
                        <div
                            className={`runbook-state${versionsQuery.isError || versionHistoryMissingCurrent ? " is-error" : ""}`}
                            role={
                                versionsQuery.isError ||
                                versionHistoryMissingCurrent
                                    ? "alert"
                                    : "status"
                            }
                        >
                            <strong>
                                {versionsQuery.isError
                                    ? "无法读取版本历史"
                                    : versionHistoryMissingCurrent
                                      ? "版本历史中缺少当前版本"
                                      : versionsQuery.isPending
                                        ? "正在读取版本历史"
                                        : "正在定位最近的已发布基准"}
                            </strong>
                            <span>
                                {versionsQuery.isError
                                    ? errorMessage(versionsQuery.error)
                                    : versionHistoryMissingCurrent
                                      ? "版本历史没有包含当前版本；重新读取前不会使用旧的发布基准。"
                                      : "先读取到当前版本及其之前的所有版本，才能确认最近的已发布基准并显示完整差异。"}
                            </span>
                            {versionsQuery.hasNextPage ? (
                                <button
                                    className="secondary-action"
                                    type="button"
                                    disabled={versionsQuery.isFetchingNextPage}
                                    onClick={() =>
                                        void versionsQuery.fetchNextPage()
                                    }
                                >
                                    {versionsQuery.isFetchingNextPage
                                        ? "正在读取版本…"
                                        : "加载更多版本"}
                                </button>
                            ) : null}
                            {versionsQuery.isError ||
                            versionHistoryMissingCurrent ? (
                                <button
                                    className="secondary-action"
                                    type="button"
                                    onClick={() => void versionsQuery.refetch()}
                                >
                                    重试读取版本历史
                                </button>
                            ) : null}
                        </div>
                    ) : null}
                    {!isNew &&
                    versionHistoryThroughCurrent &&
                    diffQuery.isPending &&
                    precedingPublished ? (
                        <p className="runbook-notice" role="status">
                            正在读取结构差异…
                        </p>
                    ) : null}
                    {!isNew && hasAuthorRole && diffQuery.isError ? (
                        <p className="runbook-field-error" role="alert">
                            无法读取发布差异：{errorMessage(diffQuery.error)}
                        </p>
                    ) : null}
                </div>

                <aside className="runbook-editor-aside" aria-label="版本操作">
                    <section className="runbook-panel runbook-action-panel">
                        <p className="eyebrow">REVIEW / RELEASE GATE</p>
                        <h2>版本治理</h2>
                        {version ? (
                            <dl className="runbook-governance-facts">
                                <div>
                                    <dt>当前责任团队</dt>
                                    <dd>{version.ownerTeam}</dd>
                                </div>
                                <div>
                                    <dt>提交人</dt>
                                    <dd>
                                        {version.authorPrincipalId ?? "未知"}
                                    </dd>
                                </div>
                                <div>
                                    <dt>独立评审</dt>
                                    <dd>
                                        {version.reviewerPrincipalId
                                            ? version.reviewerPrincipalId
                                            : "尚未完成"}
                                    </dd>
                                </div>
                                <div>
                                    <dt>内容校验</dt>
                                    <dd>{version.definitionChecksum}</dd>
                                </div>
                            </dl>
                        ) : (
                            <p className="runbook-action-hint">
                                身份信息会随首次保存固定到该 Runbook。
                            </p>
                        )}

                        {canEdit && !isNew && versionIsDraft ? (
                            <>
                                {version?.canReview &&
                                !version.reviewerPrincipalId ? (
                                    <button
                                        className="secondary-action full-action"
                                        type="button"
                                        disabled={
                                            fieldsChanged ||
                                            conflict ||
                                            isMutationPending ||
                                            Boolean(pendingCommand)
                                        }
                                        onClick={confirmIndependentReview}
                                    >
                                        确认独立评审
                                    </button>
                                ) : !version?.reviewerPrincipalId ? (
                                    <p className="runbook-notice">
                                        需由具备独立资格的管理员评审
                                    </p>
                                ) : null}
                                {version?.reviewerPrincipalId ? (
                                    <p className="runbook-reviewed-note">
                                        已由独立管理员完成评审。
                                    </p>
                                ) : null}
                                <button
                                    ref={publishTriggerRef}
                                    className="primary-action full-action"
                                    type="button"
                                    disabled={!canPublish || isMutationPending}
                                    onClick={() =>
                                        setShowPublishConfirmation(true)
                                    }
                                >
                                    发布不可变版本
                                </button>
                                {!version?.reviewerPrincipalId ? (
                                    <small className="runbook-action-hint">
                                        完成独立评审后才能发布。
                                    </small>
                                ) : fieldsChanged ? (
                                    <small className="runbook-action-hint">
                                        先保存所有编辑，再发布。
                                    </small>
                                ) : !diffReady ? (
                                    <small className="runbook-action-hint">
                                        先读取最近已发布版本的结构差异。
                                    </small>
                                ) : null}
                            </>
                        ) : null}

                        {canManageVersion &&
                        version?.lifecycle === "published" ? (
                            <button
                                className="primary-action full-action"
                                type="button"
                                disabled={
                                    isMutationPending || Boolean(pendingCommand)
                                }
                                onClick={() => void createNextDraft()}
                            >
                                创建下一版草稿
                            </button>
                        ) : null}
                        {!canManageVersion &&
                        version?.lifecycle === "published" ? (
                            <p className="runbook-action-hint">
                                此版本为只读；草稿创建由 Runbook 管理员负责。
                            </p>
                        ) : null}

                        {showPublishConfirmation && version ? (
                            <section
                                aria-describedby="publish-summary"
                                aria-labelledby="publish-confirmation-title"
                                className="runbook-publish-confirmation"
                                role="group"
                            >
                                <h3 id="publish-confirmation-title">
                                    发布版本 {version.versionNumber}
                                </h3>
                                <div id="publish-summary">
                                    <p>
                                        <strong>目标：</strong>
                                        {version.runbookKey} ·{" "}
                                        {resolvedServiceLabel} · 1 个实例
                                    </p>
                                    <p>
                                        <strong>风险：</strong>R1 · demo-http /
                                        recover_connection_pool
                                    </p>
                                    <p>
                                        <strong>验证：</strong>
                                        {
                                            version.definition.verification
                                                .attempts
                                        }{" "}
                                        次，间隔{" "}
                                        {
                                            version.definition.verification
                                                .intervalSeconds
                                        }{" "}
                                        秒
                                    </p>
                                    <p>
                                        <strong>评审人：</strong>
                                        {version.reviewerPrincipalId}
                                    </p>
                                    <p>
                                        发布后该版本不可修改；需要调整时请创建下一版草稿。
                                    </p>
                                </div>
                                <div className="runbook-confirmation-actions">
                                    <button
                                        ref={publishConfirmRef}
                                        className="primary-action"
                                        type="button"
                                        disabled={
                                            !canPublish || isMutationPending
                                        }
                                        onClick={confirmPublish}
                                    >
                                        确认发布不可变版本
                                    </button>
                                    <button
                                        className="secondary-action"
                                        type="button"
                                        onClick={() => {
                                            setShowPublishConfirmation(false);
                                            publishTriggerRef.current?.focus();
                                        }}
                                    >
                                        取消
                                    </button>
                                </div>
                            </section>
                        ) : null}

                        {conflict ? (
                            <div className="runbook-conflict" role="alert">
                                <strong>服务器版本已变化</strong>
                                <p>
                                    你的输入仍保留。刷新只会读取最新修订号，不会替换本地内容；请核对差异并合并后继续。
                                </p>
                                <button
                                    className="secondary-action"
                                    type="button"
                                    disabled={versionQuery.isFetching}
                                    onClick={() => {
                                        void versionQuery
                                            .refetch()
                                            .then((result) => {
                                                setConflictRefreshed(
                                                    !result.isError,
                                                );
                                            });
                                    }}
                                >
                                    {versionQuery.isFetching
                                        ? "正在刷新服务端版本…"
                                        : "刷新服务端版本"}
                                </button>
                                {conflictRefreshed ? (
                                    <button
                                        className="runbook-inline-button"
                                        type="button"
                                        onClick={() => {
                                            setConflict(false);
                                            setConflictRefreshed(false);
                                            setMutationError(null);
                                            setPendingCommand(null);
                                        }}
                                    >
                                        我已合并本地修改，继续编辑
                                    </button>
                                ) : null}
                            </div>
                        ) : null}

                        {pendingCommand && !conflict ? (
                            <button
                                className="secondary-action full-action"
                                type="button"
                                disabled={isMutationPending}
                                onClick={() =>
                                    void executeCommand(pendingCommand)
                                }
                            >
                                重试相同命令
                            </button>
                        ) : null}
                        {mutationError && !conflict ? (
                            <p className="runbook-field-error" role="alert">
                                {errorMessage(mutationError)}
                            </p>
                        ) : null}
                        {mutationStatus ? (
                            <p className="runbook-success" role="status">
                                {mutationStatus}
                            </p>
                        ) : null}
                        {isMutationPending ? (
                            <p className="runbook-notice" role="status">
                                正在提交版本命令…
                            </p>
                        ) : null}
                    </section>
                </aside>
            </div>
        </main>
    );
}
