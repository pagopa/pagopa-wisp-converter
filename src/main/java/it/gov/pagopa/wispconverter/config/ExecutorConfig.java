package it.gov.pagopa.wispconverter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ExecutorConfig {

    @Bean(name = "delayExecutor")
    @Primary
    public ScheduledExecutorService scheduledExecutorService() {
        // single-thread scheduled executor for retry back-offs
        return Executors.newScheduledThreadPool(1);
    }
}
