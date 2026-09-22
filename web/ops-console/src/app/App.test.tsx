import { render, screen } from "@testing-library/react";

import { App } from "./App";

it("renders the incident-first navigation", () => {
    render(<App />);

    expect(
        screen.getByRole("heading", { name: "事故中心" }),
    ).toBeInTheDocument();
    expect(
        screen.getByRole("navigation", { name: "主导航" }),
    ).toBeInTheDocument();
});

it("provides a focusable destination for the skip link", () => {
    render(<App />);

    expect(screen.getByRole("link", { name: "跳至主要内容" })).toHaveAttribute(
        "href",
        "#main-content",
    );
    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
    expect(screen.getByRole("main")).toHaveAttribute("tabindex", "-1");
});
