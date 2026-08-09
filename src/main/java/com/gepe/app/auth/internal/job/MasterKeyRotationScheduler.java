package com.gepe.app.auth.internal.job;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MasterKeyRotationScheduler {

    static final String JOB_KEY = "masterKeyRotation";
    static final String TRIGGER_KEY = "masterKeyRotationTrigger";

    @Value("${app.security.master-key-rotation-cron}")
    private String cronExpression;

    @Bean
    JobDetail masterKeyRotationJobDetail() {
        return JobBuilder.newJob(MasterKeyRotationJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably(true)
                .build();
    }

    @Bean
    Trigger masterKeyRotationTrigger(JobDetail masterKeyRotationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(masterKeyRotationJobDetail)
                .withIdentity(TRIGGER_KEY)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
