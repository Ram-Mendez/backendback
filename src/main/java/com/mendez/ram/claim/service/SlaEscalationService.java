package com.mendez.ram.claim.service;

import java.time.Instant;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimHistory;
import com.mendez.ram.claim.entity.ClaimHistoryEventType;
import com.mendez.ram.claim.repository.ClaimHistoryRepository;
import com.mendez.ram.claim.repository.ClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SlaEscalationService {
	private final ClaimRepository claims;
	private final ClaimHistoryRepository history;
	private final TransactionTemplate transactions;

	public SlaEscalationService(ClaimRepository claims, ClaimHistoryRepository history, TransactionTemplate transactions) {
		this.claims = claims;
		this.history = history;
		this.transactions = transactions;
	}

	public int processOverdueClaims(Instant now) {
		int escalated = 0;
		for (Long claimId : claims.findActiveOverdueSlaClaimIds(now)) {
			Boolean changed = transactions.execute(status -> escalateOne(claimId, now));
			if (Boolean.TRUE.equals(changed)) {
				escalated++;
			}
		}
		return escalated;
	}

	private boolean escalateOne(Long claimId, Instant now) {
		Claim claim = claims.findLockedWithUsersById(claimId).orElse(null);
		if (claim == null || !claim.breachSla(now)) {
			return false;
		}
		history.save(new ClaimHistory(claim, null, ClaimHistoryEventType.SLA_BREACHED,
				"deadline=" + claim.getSlaDeadline(), now));
		claims.flush();
		return true;
	}
}
