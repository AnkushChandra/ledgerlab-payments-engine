package com.ledgerlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerLabApplication.class, args);
    }
}
