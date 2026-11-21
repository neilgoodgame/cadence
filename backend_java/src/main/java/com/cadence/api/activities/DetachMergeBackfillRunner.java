package com.cadence.api.activities;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Issue #542's backfill for attached sources that predate PR #575 - that fix made
 * {@code ActivityMergeService#runMerge(... MergeCopyMode.ATTACH)} create a {@link Merge} row,
 * but a handful of real activities got an attached source before it existed, so they have no
 * Merge row at all and can't be found via {@code getActiveMerge}/{@code listActiveMerges} or
 * undone via {@link ActivityUnmergeService} (only the new standalone {@code detachSource} call
 * can remove them). Mirrors the Python backend's {@code backfill_detach_merges} management
 * command exactly (same {@code --mode} shape) - keep the two in sync when changing either.
 *
 * <p>Report mode (the default) only lists the orphans found; nothing is changed until
 * {@code --mode=apply} is passed. Re-running apply is idempotent - once a Merge row exists for a
 * source, {@link ActivitySourceRepository#findOrphanedAttachedSources} no longer returns it.
 *
 * <p>Every field this creates that the real attach-time code would have populated from data we
 * no longer have ({@code sourceActivity}, {@code demotedSource}, {@code offsetSeconds}, {@code
 * alignmentScore}) is left null rather than guessing - see {@code ActivitySourceService
 * #detachSource}/{@code #detachAndRestore}'s own Javadoc for why a null {@code demotedSource} is
 * safe (there's a live-data fallback), and {@link Merge#getOffsetSeconds} for why that column is
 * nullable at all.
 *
 * <pre>
 *   java -jar app.jar --backfill-detach-merges                 (report-only, every orphan)
 *   java -jar app.jar --backfill-detach-merges --activity=act_abc123
 *   java -jar app.jar --backfill-detach-merges --mode=apply
 * </pre>
 */
@Component
public class DetachMergeBackfillRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DetachMergeBackfillRunner.class);

	private final ActivitySourceRepository sourceRepository;
	private final MergeRepository mergeRepository;

	public DetachMergeBackfillRunner(ActivitySourceRepository sourceRepository, MergeRepository mergeRepository) {
		this.sourceRepository = sourceRepository;
		this.mergeRepository = mergeRepository;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!args.containsOption("backfill-detach-merges")) {
			return;
		}

		String activityId = firstValue(args, "activity");
		boolean apply = "apply".equalsIgnoreCase(firstValue(args, "mode"));

		List<ActivitySource> orphans = sourceRepository.findOrphanedAttachedSources().stream()
				.filter(s -> activityId == null || s.getActivity().getId().equals(activityId)).toList();

		if (!apply) {
			for (ActivitySource source : orphans) {
				log.info("[{}] orphaned {} source {} (origin=ATTACHED, created {})", source.getActivity().getId(),
						source.getSensorKind(), source.getId(), source.getCreatedAt());
			}
			log.info("Done (report-only - nothing changed). {} orphan(s) found.", orphans.size());
			return;
		}

		int created = 0;
		for (ActivitySource source : orphans) {
			createMerge(source);
			created++;
		}
		log.info("Done applying. {} Merge row(s) created.", created);
	}

	// Not @Transactional - a self-invoked annotation on a private method called from run() above
	// wouldn't apply anyway (Spring AOP only intercepts external calls through the bean proxy).
	// That's fine here: each iteration is just one mergeRepository.save(), which Spring Data's
	// own SimpleJpaRepository already wraps in its own transaction - there's no multi-step
	// atomicity to protect the way ActivitySourceService#detachAndRestore's swap+delete sequence
	// needs.
	private void createMerge(ActivitySource source) {
		Merge merge = new Merge();
		merge.setTargetActivity(source.getActivity());
		merge.setSourceActivity(null);
		merge.setFields(List.of(source.getSensorKind()));
		merge.setNullPolicy(null);
		merge.setCopyMode(MergeCopyMode.ATTACH);
		merge.setAttachedSource(source);
		merge.setDemotedSource(null);
		merge.setOffsetSeconds(null);
		merge.setAlignmentScore(null);
		merge.setFieldCounts(Map.of());
		merge.setPreviousActivityValues(Map.of());
		merge.setSourceHidden(false);
		// createdAt backdated to the source's own - see this class's Javadoc.
		merge.setCreatedAt(source.getCreatedAt());
		merge = mergeRepository.save(merge);
		log.info("[{}] created {} for {} source {}", source.getActivity().getId(), merge.getId(),
				source.getSensorKind(), source.getId());
	}

	private String firstValue(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return (values == null || values.isEmpty()) ? null : values.get(0);
	}
}
