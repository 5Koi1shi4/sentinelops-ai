import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./app/App";
import { queryClient } from "./app/queryClient";
import "./styles/index.css";

const root = document.getElementById("root");

if (!root) {
    throw new Error("SentinelOps console root element was not found");
}

createRoot(root).render(
    <StrictMode>
        <QueryClientProvider client={queryClient}>
            <App />
        </QueryClientProvider>
    </StrictMode>,
);
