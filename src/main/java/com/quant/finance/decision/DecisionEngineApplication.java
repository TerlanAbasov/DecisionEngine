package com.quant.finance.decision;

import com.quant.finance.decision.client.ExecutionEngineFeignClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableFeignClients(clients = {ExecutionEngineFeignClient.class})
public class DecisionEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(DecisionEngineApplication.class, args);
    }
}
