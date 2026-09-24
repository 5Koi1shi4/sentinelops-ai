@org.springframework.modulith.ApplicationModule(
        displayName = "Execution Control",
        allowedDependencies = {
            "identity :: application",
            "incident :: domain",
            "knowledge :: domain",
            "shared :: id",
            "shared :: audit",
            "shared :: idempotency",
            "shared :: problem",
            "shared :: config",
            "shared :: time",
            "shared :: observability"
        })
package io.sentinelops.api.execution;
