package com.vtnexus.notebook.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.vtnexus.notebook.execution", "com.vtnexus.notebook.common"})
public class ExecutionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExecutionServiceApplication.class, args);
    }
}
