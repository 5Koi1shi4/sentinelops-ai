package io.sentinelops.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SentinelOpsExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelOpsExecutorApplication.class, args);
    }
}
