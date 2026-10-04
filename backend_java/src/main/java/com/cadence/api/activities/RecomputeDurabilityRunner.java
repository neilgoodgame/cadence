package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Backfills aerobic decoupling and durability (ActivityDurability rows) across existing
 * activities - for bootstrapping the feature against history, or after a fix to the computation
 * itself. Mirrors the Python backend's recompute_durability management command exactly
 * (same {@code --athlete}/{@code --since} arguments, same qualified/not-qualified/errored
 * counts logged at the end), as an {@link ApplicationRunner} gated behind an explicit
 * {@code --recompute-durability} flag so normal application boot is completely unaffected -
 * Java has no management-command framework equivalent to {@code manage.py}, and this is the
 * closest operator-triggered equivalent ("Java: equivalent job" per the feature spec) without
 * standing up a whole second entry point.
 *
 * <pre>
 *   java -jar app.jar --recompute-durability
 *   java -jar app.jar --recompute-durability --athlete=ath_abc123
 *   java -jar app.jar --recompute-durability --since=2026-01-01
 * </pre>
 */
@Component
public class RecomputeDurabilityRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RecomputeDurabilityRunner.class);
	private static final List<Sport> DECOUPLING_SPORTS = List.of(Sport.BIKE, Sport.RUN);

	private final ActivityRepository activityRepository;
	private final ActivityDecouplingService decouplingService;

	public RecomputeDurabilityRunner(ActivityRepository activityRepository, ActivityDecouplingService decouplingService) {
		this.activityRepository = activityRepository;
		this.decouplingService = decouplingService;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!args.containsOption("recompute-durability")) {
			return;
		}

		String athleteId = firstValue(args, "athlete");
		Instant since = null;
		String sinceArg = firstValue(args, "since");
		if (sinceArg != null) {
			since = LocalDate.parse(sinceArg).atStartOfDay(ZoneOffset.UTC).toInstant();
		}

		List<Activity> activities = activityRepository.findDecouplingBackfillCandidates(DECOUPLING_SPORTS, athleteId, since);
		int total = activities.size();
		int qualified = 0;
		int notQualified = 0;
		int errored = 0;

		for (int i = 0; i < total; i++) {
			Activity activity = activities.get(i);
			try {
				decouplingService.computeAndPersist(activity, activity.getAthlete());
				if (activity.isDecouplingQualified()) {
					qualified++;
				}
				else {
					notQualified++;
				}
			}
			catch (Exception e) {
				errored++;
				log.warn("recompute_durability failed for activity {}", activity.getId(), e);
			}
			if ((i + 1) % 100 == 0 || i + 1 == total) {
				log.info("{}/{}...", i + 1, total);
			}
		}

		log.info("Done. {} activities: {} qualified, {} not qualified, {} errored.", total, qualified, notQualified, errored);
	}

	private String firstValue(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return (values == null || values.isEmpty()) ? null : values.get(0);
	}
}
