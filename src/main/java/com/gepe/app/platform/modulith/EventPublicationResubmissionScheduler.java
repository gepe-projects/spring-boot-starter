package com.gepe.app.platform.modulith;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EventPublicationResubmissionScheduler {

	static final String JOB_KEY = "modulithEventPublicationResubmission";
	static final String TRIGGER_KEY = "modulithEventPublicationResubmissionTrigger";
	static final int RESUBMISSION_INTERVAL_MINUTES = 5;

	@Bean
	JobDetail modulithEventPublicationResubmissionJobDetail() {
		return JobBuilder.newJob(EventPublicationResubmissionJob.class)
				.withIdentity(JOB_KEY)
				.storeDurably(true)
				.build();
	}

	@Bean
	Trigger modulithEventPublicationResubmissionTrigger(JobDetail modulithEventPublicationResubmissionJobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(modulithEventPublicationResubmissionJobDetail)
				.withIdentity(TRIGGER_KEY)
				.startNow()
				.withSchedule(SimpleScheduleBuilder.simpleSchedule()
						.withIntervalInMinutes(RESUBMISSION_INTERVAL_MINUTES)
						.repeatForever()
						.withMisfireHandlingInstructionNowWithExistingCount())
				.build();
	}
}
