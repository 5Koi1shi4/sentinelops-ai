import { useEffect, useRef } from "react";
import {
    init,
    use as registerCharts,
    type EChartsCoreOption,
} from "echarts/core";
import { LineChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

import type { EvalTrendPoint } from "./evalApi";

registerCharts([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

type TrendMetric = "accuracy" | "latencyMs" | "costUsd";

interface MetricChartProps {
    label: string;
    points: EvalTrendPoint[];
    metric: TrendMetric;
    color: string;
    unit: "percent" | "ms" | "usd";
}

function formatValue(value: unknown, unit: MetricChartProps["unit"]): string {
    if (typeof value !== "number" || !Number.isFinite(value)) {
        return "不可用";
    }
    if (unit === "percent") {
        return `${new Intl.NumberFormat("zh-CN", {
            maximumFractionDigits: 1,
        }).format(value)}%`;
    }
    if (unit === "ms") {
        return `${new Intl.NumberFormat("zh-CN").format(value)} ms`;
    }
    return `USD ${new Intl.NumberFormat("en-US", {
        minimumFractionDigits: 6,
        maximumFractionDigits: 6,
    }).format(value)}`;
}

function timestampLabel(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "时间未知";
    }
    return new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        timeZone: "UTC",
    }).format(date);
}

function optionFor({
    points,
    metric,
    color,
    unit,
}: MetricChartProps): EChartsCoreOption {
    const isRate = unit === "percent";
    return {
        animation: false,
        color: [color],
        grid: { top: 10, right: 14, bottom: 34, left: 48, containLabel: true },
        tooltip: {
            trigger: "axis",
            valueFormatter: (value: unknown) => formatValue(value, unit),
        },
        xAxis: {
            type: "category",
            data: points.map((point) => timestampLabel(point.startedAt)),
            axisLabel: { color: "#a8bdc5", hideOverlap: true },
            axisLine: { lineStyle: { color: "#3d5c68" } },
        },
        yAxis: {
            type: "value",
            min: isRate ? 0 : undefined,
            max: isRate ? 100 : undefined,
            axisLabel: {
                color: "#a8bdc5",
                formatter: (value: number) =>
                    unit === "percent"
                        ? `${value}%`
                        : unit === "ms"
                          ? `${value} ms`
                          : `USD ${value}`,
            },
            splitLine: { lineStyle: { color: "rgba(61, 92, 104, 0.42)" } },
        },
        series: [
            {
                type: "line",
                data: points.map((point) => point[metric]),
                smooth: false,
                connectNulls: false,
                showSymbol: true,
                symbolSize: 6,
                lineStyle: { width: 2 },
                emphasis: { focus: "series" },
            },
        ],
    };
}

function MetricChart({ label, points, metric, color, unit }: MetricChartProps) {
    const container = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const element = container.current;
        if (!element || points.length === 0) {
            return;
        }

        const chart = init(element, null, { renderer: "canvas" });
        chart.setOption(optionFor({ label, points, metric, color, unit }));
        const resize = () => chart.resize();
        const resizeObserver =
            typeof ResizeObserver === "undefined"
                ? null
                : new ResizeObserver(resize);

        if (resizeObserver) {
            resizeObserver.observe(element);
        } else {
            window.addEventListener("resize", resize, { passive: true });
        }

        return () => {
            resizeObserver?.disconnect();
            if (!resizeObserver) {
                window.removeEventListener("resize", resize);
            }
            chart.dispose();
        };
    }, [color, label, metric, points, unit]);

    return (
        <section className="eval-chart-card" aria-label={label}>
            <h4>{label}</h4>
            {points.length > 0 ? (
                <div
                    aria-hidden="true"
                    className="eval-chart-canvas"
                    ref={container}
                />
            ) : (
                <p className="eval-chart-empty">尚无数据</p>
            )}
        </section>
    );
}

export default function EvalTrendCharts({
    points,
}: {
    points: EvalTrendPoint[];
}) {
    if (points.length === 0) {
        return <p className="eval-chart-empty">尚无已完成运行可供绘图。</p>;
    }

    return (
        <div className="eval-chart-grid" aria-label="评估指标趋势图">
            <MetricChart
                color="#45e0ca"
                label="Runbook 准确率（%）"
                metric="accuracy"
                points={points}
                unit="percent"
            />
            <MetricChart
                color="#f5c451"
                label="累计延迟（ms）"
                metric="latencyMs"
                points={points}
                unit="ms"
            />
            <MetricChart
                color="#a8bdc5"
                label="评估成本（USD）"
                metric="costUsd"
                points={points}
                unit="usd"
            />
        </div>
    );
}
