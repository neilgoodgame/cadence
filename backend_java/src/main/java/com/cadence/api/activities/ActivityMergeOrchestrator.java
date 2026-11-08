package com.cadence.api.activities;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Issue #501 (phase 3): recovers a preview's result from {@link MergePreviewRollbackSignal} on
 * behalf of a caller that can't catch it itself - either because it's outside this package (the
 * signal is package-private by design) or because it's {@link ActivityMergeService} itself,
 * which can't: {@code runMerge}'s {@code @Transactional} only applies through the Spring proxy,
 * and a same-class call bypasses that proxy entirely (self-invocation - see backend_java/
 * CLAUDE.md's own gotcha), so a {@code try/catch} wrapper living inside {@code
 * ActivityMergeService} would call the *unproxied* {@code runMerge} and the preview's writes
 * would never roll back. This class exists only so the call crosses a real bean boundary.
 * {@code ActivityController}'s REST handler keeps its own inline catch (it's already a separate
 * bean) rather than switching to this - this is for {@code merge_activities} (MCP), a different
 * package that can't name the signal type at all.
 */
@Service
public class ActivityMergeOrchestrator {

	private final ActivityMergeService mergeService;

	public ActivityMergeOrchestrator(ActivityMergeService mergeService) {
		this.mergeService = mergeService;
	}

	public ActivityMergeService.MergeResult merge(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, Integer offsetSecondsOverride, boolean hideSource, boolean preview) {
		return merge(targetId, sourceId, fields, nullPolicy, offsetSecondsOverride, hideSource, preview,
				MergeCopyMode.OVERWRITE);
	}

	/** Issue #503 (phase 1): {@code copyMode}'s own ATTACH branch never throws {@link
	 * MergePreviewRollbackSignal} (see {@code ActivityMergeService.runAttach}'s own Javadoc), but
	 * this catch stays unconditional - the same method handles both copy modes, and OVERWRITE
	 * still relies on it. */
	public ActivityMergeService.MergeResult merge(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, Integer offsetSecondsOverride, boolean hideSource, boolean preview,
			MergeCopyMode copyMode) {
		try {
			return mergeService.runMerge(targetId, sourceId, fields, nullPolicy, offsetSecondsOverride, hideSource,
					preview, copyMode);
		}
		catch (MergePreviewRollbackSignal signal) {
			return signal.result();
		}
	}
}
