package com.cadence.api.activities;

import com.cadence.api.activities.ActivityOverlapDetectionService.OverlapResolution;
import com.cadence.api.activities.dto.OverlapPairResponse;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistoryService;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Issue #498's backfill for same-ride-twice duplicates that existed before on-ingest detection
 * ({@code ActivityOverlapDetectionService}, wired into {@code ParseFileTasklet}) shipped - that
 * only runs against newly-ingested activities, so every pair already sitting in the database
 * needs this separate, operator-triggered pass. Mirrors the Python backend's {@code
 * backfill_overlaps} management command exactly (same {@code --athlete}/{@code --mode}/{@code
 * --action} arguments, same report-then-apply shape and recompute cascade), as an {@link
 * ApplicationRunner} gated behind an explicit flag so normal application boot is unaffected -
 * same reasoning as {@link RecomputeDurabilityRunner}'s own Javadoc.
 *
 * <p>Report mode (the default) only lists what it finds, exactly as the issue asks for
 * ("report-only mode first, then apply"); nothing is changed until {@code --mode=apply} is
 * passed. Apply mode resolves every unresolved pair via {@code
 * ActivityOverlapDetectionService.resolvePair} - the same code path on-ingest detection uses - then,
 * once per athlete that actually had a pair resolved, rebuilds that athlete's threshold history
 * (duplicate-sourced best efforts are no longer eligible threshold candidates) and recomputes
 * TSS/intensity for their activities against the rebuilt ledger (the fitness trend reads {@code
 * Activity.tss} live, so re-rating activities is also how the fitness trend "recomputes" - there's
 * no separate stored series to rebuild).
 *
 * <pre>
 *   java -jar app.jar --backfill-overlaps                       (report-only, every athlete)
 *   java -jar app.jar --backfill-overlaps --athlete=ath_abc123
 *   java -jar app.jar --backfill-overlaps --mode=apply           (defaults to --action=mark)
 *   java -jar app.jar --backfill-overlaps --mode=apply --action=flag
 *   java -jar app.jar --backfill-overlaps --mode=apply --action=merge
 * </pre>
 *
 * <p>{@code --action=merge} (issue #501 phase 4) resolves each pair via the athlete's saved-or-
 * built-in {@link MergeRuleService} rule, same as on-ingest detection - see {@link
 * ActivityOverlapDetectionService}'s own Javadoc for the full merge-vs-flag decision.
 */
@Component
public class OverlapBackfillRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(OverlapBackfillRunner.class);
	private static final int PAGE_SIZE = 100;

	private final UserRepository userRepository;
	private final ActivityOverlapService overlapService;
	private final ActivityOverlapDetectionService detectionService;
	private final ThresholdHistoryService thresholdHistoryService;
	private final TssRecomputeService tssRecomputeService;

	public OverlapBackfillRunner(UserRepository userRepository, ActivityOverlapService overlapService,
			ActivityOverlapDetectionService detectionService, ThresholdHistoryService thresholdHistoryService,
			TssRecomputeService tssRecomputeService) {
		this.userRepository = userRepository;
		this.overlapService = overlapService;
		this.detectionService = detectionService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.tssRecomputeService = tssRecomputeService;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!args.containsOption("backfill-overlaps")) {
			return;
		}

		String athleteId = firstValue(args, "athlete");
		boolean apply = "apply".equalsIgnoreCase(firstValue(args, "mode"));
		String actionArg = firstValue(args, "action");
		OverlapHandling action = "merge".equalsIgnoreCase(actionArg) ? OverlapHandling.MERGE
				: "flag".equalsIgnoreCase(actionArg) ? OverlapHandling.FLAG : OverlapHandling.MARK;

		List<User> athletes = athleteId != null
				? List.of(userRepository.findById(athleteId)
						.orElseThrow(() -> new IllegalArgumentException("No such athlete: " + athleteId)))
				: userRepository.findAll();

		int totalPairs = 0;
		int marked = 0;
		int merged = 0;
		int mergeSkipped = 0;
		int flagged = 0;
		Set<String> affectedAthleteIds = new LinkedHashSet<>();

		for (User athlete : athletes) {
			String cursor = null;
			while (true) {
				CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
						athlete.getId(), null, null, false, null, null, null, null, null, null,
						List.of(), null, null, false, cursor, PAGE_SIZE);
				for (OverlapPairResponse pair : page.data()) {
					totalPairs++;
					String anchorId = pair.anchor().id();
					String otherId = pair.other().id();
					if (!apply) {
						log.info("[{}] {} <-> {} (overlap {}%, start delta {}s)", athlete.getId(), anchorId, otherId,
								pair.overlapPct(), pair.startDeltaSeconds());
						continue;
					}
					OverlapResolution resolution = detectionService.resolvePair(anchorId, otherId, action);
					switch (resolution.action()) {
						case MARKED -> {
							marked++;
							affectedAthleteIds.add(athlete.getId());
							log.info("[{}] MARKED {} as a duplicate of {}", athlete.getId(),
									resolution.otherActivityId(), anchorId);
						}
						// Not added to affectedAthleteIds - the merge itself already ran the full
						// recompute cascade (see ActivityOverlapDetectionService.merge).
						case MERGED -> {
							merged++;
							log.info("[{}] MERGED {} into {}", athlete.getId(), resolution.otherActivityId(),
									resolution.primaryActivityId());
						}
						case MERGE_SKIPPED_LOW_SCORE -> {
							mergeSkipped++;
							log.info("[{}] MERGE_SKIPPED_LOW_SCORE {} <-> {}", athlete.getId(), anchorId, otherId);
						}
						default -> {
							flagged++;
							log.info("[{}] FLAGGED {} <-> {}", athlete.getId(), anchorId, otherId);
						}
					}
				}
				if (!page.hasMore()) {
					break;
				}
				cursor = page.nextCursor();
			}
		}

		if (!apply) {
			log.info("Done (report-only - nothing changed). {} unresolved pair(s) found across {} athlete(s).",
					totalPairs, athletes.size());
			return;
		}

		log.info("Done applying. {} pair(s) resolved: {} marked, {} merged, {} merge-skipped (low score), {} flagged.",
				totalPairs, marked, merged, mergeSkipped, flagged);

		for (String id : affectedAthleteIds) {
			User athlete = userRepository.findById(id).orElseThrow();
			for (ThresholdField field : ThresholdField.values()) {
				thresholdHistoryService.rebuildHistory(athlete, field, (current, total) -> { });
			}
			int rerated = tssRecomputeService.recomputeForAthlete(athlete);
			log.info("[{}] Rebuilt threshold history and re-rated {} activity/activities.", id, rerated);
		}
	}

	private String firstValue(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return (values == null || values.isEmpty()) ? null : values.get(0);
	}
}
