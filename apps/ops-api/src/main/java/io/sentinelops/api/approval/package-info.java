@org.springframework.modulith.ApplicationModule(
        displayName = "Approval",
        allowedDependencies = {
            "diagnosis :: domain",
            "identity :: application",
            "incident :: domain",
            "knowledge :: domain",
            "shared :: id",
            "shared :: audit",
            "shared :: idempotency",
            "shared :: problem"
        })
package io.sentinelops.api.approval;
