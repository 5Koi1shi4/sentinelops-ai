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
