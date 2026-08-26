package com.quantplat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuantPlatApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuantPlatApplication.class, args);
    }
}
