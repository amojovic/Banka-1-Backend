package com.banka1.order.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Dedicated scheduler for delayed order execution attempts so async workers are not blocked.
 */
@Configuration
@Slf4j
public class OrderExecutionSchedulingConfig {

    @Bean
    public TaskScheduler orderExecutionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("order-execution-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(t -> log.error("Uncaught exception in order execution scheduler task", t));
        scheduler.initialize();
        return scheduler;
    }
}
