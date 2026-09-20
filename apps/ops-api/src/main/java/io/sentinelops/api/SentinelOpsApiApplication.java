package io.sentinelops.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

@Modulith
@SpringBootApplication
public class SentinelOpsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelOpsApiApplication.class, args);
    }
}
