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
class SigningKeyRotationScheduler {

    static final String JOB_KEY = "signingKeyRotation";
    static final String TRIGGER_KEY = "signingKeyRotationTrigger";

    @Value("${app.security.signing-key-rotation-cron}")
    private String cronExpression;

    @Bean
    JobDetail signingKeyRotationJobDetail() {
        return JobBuilder.newJob(SigningKeyRotationJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably(true)
                .build();
    }

    @Bean
    Trigger signingKeyRotationTrigger(JobDetail signingKeyRotationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(signingKeyRotationJobDetail)
                .withIdentity(TRIGGER_KEY)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
