package com.gepe.app.auth.internal.job;

import com.gepe.app.auth.internal.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;

@DisallowConcurrentExecution
@RequiredArgsConstructor
public class RefreshTokenCleanupJob extends QuartzJobBean {

    private final RefreshTokenService refreshTokenService;


    @Override
    protected void executeInternal(JobExecutionContext context) {refreshTokenService.cleanup();}
}
