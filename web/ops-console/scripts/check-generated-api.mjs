import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const contract = resolve(
    appRoot,
    "../../contracts/openapi/sentinelops-api.yaml",
);
const committed = resolve(appRoot, "src/api/generated.ts");
const buildDirectory = resolve(appRoot, "../../target");
await mkdir(buildDirectory, { recursive: true });
const temporaryDirectory = await mkdtemp(
    join(buildDirectory, "sentinelops-api-"),
);
const candidate = join(temporaryDirectory, "generated.ts");

function runNodeScript(script, args) {
    const result = spawnSync(process.execPath, [script, ...args], {
        cwd: appRoot,
        stdio: "inherit",
    });
    if (result.error) {
        throw result.error;
    }
    if (result.status !== 0) {
        throw new Error(`${script} exited with status ${result.status}`);
    }
}

try {
    runNodeScript(
        resolve(appRoot, "node_modules/openapi-typescript/bin/cli.js"),
        [contract, "-o", candidate],
    );
    runNodeScript(resolve(appRoot, "node_modules/prettier/bin/prettier.cjs"), [
        "--write",
        candidate,
    ]);

    const [expected, actual] = await Promise.all([
        readFile(candidate, "utf8"),
        readFile(committed, "utf8"),
    ]);
    if (expected !== actual) {
        console.error(
            "Generated API types are stale. Run `npm run api:generate` and commit the result.",
        );
        process.exitCode = 1;
    }
} finally {
    await rm(temporaryDirectory, { recursive: true, force: true });
}
