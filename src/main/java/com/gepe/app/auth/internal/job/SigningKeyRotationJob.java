package com.gepe.app.auth.internal.job;

import com.gepe.app.auth.internal.service.SigningKeyRotationService;
import lombok.RequiredArgsConstructor;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

@DisallowConcurrentExecution
@RequiredArgsConstructor
class SigningKeyRotationJob extends QuartzJobBean {

    private final SigningKeyRotationService rotationService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        rotationService.rotate();
    }
}
