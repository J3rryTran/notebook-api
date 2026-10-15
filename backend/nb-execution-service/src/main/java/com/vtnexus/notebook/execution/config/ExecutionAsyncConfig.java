package com.vtnexus.notebook.execution.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

@Configuration
public class ExecutionAsyncConfig {

    @Bean(name = "eventsTaskExecutor")
    public AsyncTaskExecutor eventsTaskExecutor(com.vtnexus.notebook.common.config.NotebookProperties notebookProperties) {
        int poolSize = notebookProperties.getEvents().getPoolSize();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize * 2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("nb-events-");
        executor.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }
}
