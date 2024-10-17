package it.gov.pagopa.wispconverter.scheduler;

import it.gov.pagopa.wispconverter.service.RecoveryService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static it.gov.pagopa.wispconverter.util.SchedulerUtils.*;

@Component
@Slf4j
@ConditionalOnProperty(name = "cron.job.schedule.recovery.enabled", matchIfMissing = false)
public class RecoveryScheduler {

    @Value("${cron.job.schedule.recovery.hours.ago.from}")
    private int fromHoursAgo;

    @Value("${cron.job.schedule.recovery.hours.ago.until}") // untilHoursAgo: upperbound for a payment session
    private int untilHoursAgo;

    private final RecoveryService recoveryService;

    @Autowired
    public RecoveryScheduler(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Getter
    private Thread threadOfExecution;

    // https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/support/CronExpression.html
    @Scheduled(cron = "${cron.job.schedule.recovery.receipt-ko.trigger}")
    @Async
    public void recoverReceiptKOCronJob() {
    	updateMDCForStartExecution("recoverReceiptKOCronJob", "fromHoursAgo="+fromHoursAgo+"; untilHoursAgo="+untilHoursAgo);
    	try {
    		ZonedDateTime dateFrom = ZonedDateTime.now(ZoneOffset.UTC).minusHours(fromHoursAgo);
    		ZonedDateTime dateTo = ZonedDateTime.now(ZoneOffset.UTC).minusHours(untilHoursAgo); 
    		log.debug("[WISP-Recovery][Scheduled] Reconciliation Cron: recoverReceiptKOCronJob running at {}, for recover stale RPT from {} to {}",
    				DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()), dateFrom, dateTo);

    		int missingRTRecovered = this.recoveryService.recoverReceiptKOByDate(dateFrom, dateTo).getPayments().size();
    		int missingRedirectRecovered = this.recoveryService.recoverMissingRedirect(dateFrom, dateTo);

    		log.debug("[WISP-Recovery][Scheduled] Reconciliation Cron: recoverReceiptKOCronJob {} receipt-ko sent", missingRedirectRecovered + missingRTRecovered);
    		this.threadOfExecution = Thread.currentThread();
    		updateMDCForEndExecution();
    	}
    	catch (Exception e) {
    		updateMDCError(e, "recoverReceiptKOCronJob error");
    		throw e;
    	}
    	finally {
    		MDC.clear();
    	}
    }
}
