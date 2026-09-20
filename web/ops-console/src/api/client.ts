export interface ProblemDetail {
    type?: string;
    title?: string;
    status?: number;
    detail?: string;
    instance?: string;
    [extension: string]: unknown;
}

export class ApiProblem extends Error {
    readonly status: number;
    readonly problem: ProblemDetail;

    constructor(status: number, problem: ProblemDetail) {
        super(
            problem.detail ??
                problem.title ??
                `API request failed with ${status}`,
        );
        this.name = "ApiProblem";
        this.status = status;
        this.problem = problem;
    }
}

export async function apiFetch<T>(
    path: string,
    init: RequestInit = {},
): Promise<T> {
    const headers = new Headers(init.headers);
    const apiPath = path.startsWith("/") ? path : `/${path}`;

    if (!headers.has("Accept")) {
        headers.set("Accept", "application/json, application/problem+json");
    }

    const response = await fetch(`/api/v1${apiPath}`, {
        ...init,
        headers,
    });

    if (!response.ok) {
        const contentType = response.headers.get("content-type") ?? "";
        const problem = contentType.includes("json")
            ? ((await response.json()) as ProblemDetail)
            : { status: response.status, title: response.statusText };

        throw new ApiProblem(response.status, problem);
    }

    if (response.status === 204) {
        return undefined as T;
    }

    return (await response.json()) as T;
}
