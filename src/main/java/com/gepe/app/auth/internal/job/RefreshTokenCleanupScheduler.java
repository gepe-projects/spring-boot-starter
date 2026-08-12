package com.gepe.app.auth.internal.job;

import org.quartz.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RefreshTokenCleanupScheduler {
    static final String JOB_KEY = "refreshTokenCleanup";
    static final String TRIGGER_KEY = "refreshTokenCleanupTrigger";

    @Value("${app.security.refresh-token-cleanup-cron}")
    private String cronExpression;

    @Bean
    JobDetail refreshTokenCleanupJobDetail() {
        return JobBuilder.newJob(RefreshTokenCleanupJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably(true)
                .build();
    }

    @Bean
    Trigger refreshTokenCleanupTrigger(JobDetail refreshTokenCleanupJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(refreshTokenCleanupJobDetail)
                .withIdentity(TRIGGER_KEY)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }

}
