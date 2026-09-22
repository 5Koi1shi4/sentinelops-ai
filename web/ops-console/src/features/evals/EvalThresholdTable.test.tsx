import { render, screen } from "@testing-library/react";

import type { components } from "../../api/generated";
import { EvalThresholdTable } from "./EvalThresholdTable";

type EvalAggregateMetrics = components["schemas"]["EvalAggregateMetrics"];

it("blocks release when any safety hard threshold fails", () => {
    render(
        <EvalThresholdTable
            metrics={{
                citationResolvableRate: 0.99,
                dangerousActionBlockRate: 0.99,
                runbookAccuracy: 0.84,
                rootCauseTop3Accuracy: 0.79,
                fictionalToolCount: 1,
            }}
        />,
    );

    expect(screen.getAllByText("发布阻断")).toHaveLength(5);
    expect(screen.getByText(/引用可解析率/)).toBeVisible();
    expect(screen.getByText(/危险动作拦截率/)).toBeVisible();
    expect(screen.getByText(/Runbook 准确率/)).toBeVisible();
    expect(screen.getByText(/根因 Top-3 准确率/)).toBeVisible();
    expect(screen.getByText(/虚构工具调用/)).toBeVisible();
});

it("shows missing or null metrics as unknown instead of treating them as zero", () => {
    const metrics = {
        citationResolvableRate: null,
        dangerousActionBlockRate: null,
        fictionalToolCount: undefined,
    } as unknown as EvalAggregateMetrics;

    render(<EvalThresholdTable metrics={metrics} />);

    expect(screen.getAllByText("未知")).toHaveLength(5);
    expect(screen.queryByText("0% · 发布阻断")).not.toBeInTheDocument();
});

it("does not round a near-failing hard metric up to its threshold", () => {
    render(
        <EvalThresholdTable
            metrics={{
                citationResolvableRate: 0.999998,
                dangerousActionBlockRate: 0.999999,
                runbookAccuracy: 0.8499999,
                rootCauseTop3Accuracy: 0.7999999,
                fictionalToolCount: 0,
            }}
        />,
    );

    expect(screen.getByText("99.9998%")).toBeVisible();
    expect(screen.getByText("99.9999%")).toBeVisible();
    expect(screen.getByText("<85%")).toBeVisible();
    expect(screen.getByText("<80%")).toBeVisible();
});
