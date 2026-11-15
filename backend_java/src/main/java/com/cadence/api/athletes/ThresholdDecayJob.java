package com.cadence.api.athletes;

import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily companion (02:17 UTC) to the ingest/manual-edit/refresh/rebuild triggers already wired
 * through {@link ThresholdDecayRecomputeService#resimulateSuffix} (issue #546) - those four
 * already re-simulate a field's suffix through today whenever they run, so a stale ledger
 * self-corrects the next time any of them fires. This job only matters for an athlete who goes
 * quiet on one sport but not another (e.g. a cyclist who stops riding but keeps running would
 * never see ftp decay without it). Issue #548, split out of #485. Skips every athlete with
 * thresholdDecayEnabled=false (the default, and so the common case today).
 */
@Component
public class ThresholdDecayJob {

	private static final Logger log = LoggerFactory.getLogger(ThresholdDecayJob.class);

	private final UserRepository userRepository;
	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final ThresholdDecayRecomputeService recomputeService;
	private final EntityManager entityManager;

	public ThresholdDecayJob(UserRepository userRepository, ThresholdHistoryRepository thresholdHistoryRepository,
			ThresholdDecayRecomputeService recomputeService, EntityManager entityManager) {
		this.userRepository = userRepository;
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.recomputeService = recomputeService;
		this.entityManager = entityManager;
	}

	@Scheduled(cron = "0 17 2 * * *")
	public void applyThresholdDecay() {
		for (User athlete : userRepository.findByThresholdDecayEnabledTrue()) {
			try {
				processAthlete(athlete);
			} catch (Exception ex) {
				log.error("Threshold decay daily job failed for athlete {}", athlete.getId(), ex);
			}
			// Load-bearing, not cosmetic (same reasoning as ThresholdHistoryCalculator's own
			// per-activity entityManager.clear()) - staging runs with a 600MB cap, and
			// resimulateSuffix can load a whole trailing window of Activity/Record rows per
			// athlete per field.
			entityManager.clear();
		}
	}

	@Transactional
	void processAthlete(User athlete) {
		LocalDate today = LocalDate.now();
		LocalDate expiry = today.minusDays(athlete.getThresholdWindowDays());
		for (ThresholdField field : ThresholdField.values()) {
			thresholdHistoryRepository
					.findFirstByAthleteIdAndFieldAndEffectiveFromLessThanAndKindNotOrderByEffectiveFromDescIdDesc(
							athlete.getId(), field, today, ThresholdHistoryKind.DECAYED)
					.filter(anchor -> anchor.getEffectiveFrom().isBefore(expiry))
					.ifPresent(anchor -> recomputeService.resimulateSuffix(athlete, field, today));
		}
	}
}
