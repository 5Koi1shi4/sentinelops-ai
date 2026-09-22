import js from "@eslint/js";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
    { ignores: ["dist", "coverage"] },
    js.configs.recommended,
    ...tseslint.configs.recommended,
    {
        files: ["scripts/**/*.mjs", "playwright.config.ts", "e2e/**/*.ts"],
        languageOptions: {
            ecmaVersion: 2022,
            globals: globals.node,
        },
    },
    {
        files: ["**/*.{ts,tsx}"],
        languageOptions: {
            ecmaVersion: 2022,
            globals: globals.browser,
        },
        plugins: {
            "react-hooks": reactHooks,
            "react-refresh": reactRefresh,
        },
        rules: {
            ...reactHooks.configs.flat.recommended.rules,
            ...reactRefresh.configs.vite.rules,
        },
    },
);
