package com.jira.autoassign.scheduler;

import com.jira.autoassign.service.AssignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssignScheduler {

    private final AssignService assignService;

    /**
     * Triggered automatically based on the cron expression in application.properties.
     * Default: every 30 minutes  ->  0 *\/30 * * * *
     *
     * To change frequency, update jira.schedule.cron in application.properties.
     * Examples:
     *   Every 10 min  : 0 *\/10 * * * *
     *   Every hour    : 0 0 * * * *
     *   Daily 9 AM    : 0 0 9 * * *
     */
    @Scheduled(cron = "${jira.schedule.cron}")
    public void scheduledRun() {
        log.info("Scheduler triggered — starting assignment run.");
        assignService.runAssignment();
    }
}
