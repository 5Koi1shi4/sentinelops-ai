@org.springframework.modulith.ApplicationModule(
        displayName = "Execution Control",
        allowedDependencies = {
            "identity :: application",
            "incident :: domain",
            "knowledge :: domain",
            "shared :: id",
            "shared :: idempotency",
            "shared :: problem",
            "shared :: time"
        })
package io.sentinelops.api.execution;
