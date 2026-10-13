package com.vtnexus.notebook.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.vtnexus.notebook.store", "com.vtnexus.notebook.common"})
public class NotebookServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotebookServiceApplication.class, args);
    }
}
