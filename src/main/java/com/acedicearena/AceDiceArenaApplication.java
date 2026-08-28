package com.acedicearena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AceDiceArenaApplication {
    public static void main(String[] args) {
        SpringApplication.run(AceDiceArenaApplication.class, args);
    }
}
