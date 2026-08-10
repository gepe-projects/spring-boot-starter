package com.gepe.app.platform.modulith;

import java.time.Duration;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.quartz.QuartzJobBean;

@DisallowConcurrentExecution
class EventPublicationResubmissionJob extends QuartzJobBean {

	static final int MAX_ATTEMPTS = 10;

	private final FailedEventPublications failedEventPublications;

	EventPublicationResubmissionJob(FailedEventPublications failedEventPublications) {
		this.failedEventPublications = failedEventPublications;
	}

	@Override
	protected void executeInternal(JobExecutionContext context) {
		failedEventPublications.resubmit(ResubmissionOptions.defaults()
				.withMaxInFlight(20)
				.withBatchSize(50)
				.withMinAge(Duration.ofMinutes(1))
				.withFilter(p -> p.getCompletionAttempts() < MAX_ATTEMPTS));
	}
}
