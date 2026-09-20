package io.sentinelops.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableScheduling;

@Modulith
@EnableScheduling
@SpringBootApplication
public class SentinelOpsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelOpsApiApplication.class, args);
    }
}
