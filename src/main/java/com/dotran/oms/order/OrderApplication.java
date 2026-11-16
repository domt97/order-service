package com.dotran.oms.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Order bounded context (ADR 0007). Scans {@code com.dotran.oms} so the oms-core beans (request id, MDC, Swagger) load. */
@SpringBootApplication(scanBasePackages = "com.dotran.oms")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
