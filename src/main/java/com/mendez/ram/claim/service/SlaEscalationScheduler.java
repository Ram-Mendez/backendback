package com.mendez.ram.claim.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ram.sla.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SlaEscalationScheduler {
	private final SlaEscalationService escalationService;
	private final Clock clock;

	public SlaEscalationScheduler(SlaEscalationService escalationService, Clock clock) {
		this.escalationService = escalationService;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${ram.sla.scan-delay-ms:60000}")
	public void processOverdueClaims() {
		escalationService.processOverdueClaims(Instant.now(clock));
	}
}
