package com.cadence.api.activities;

import com.cadence.api.activities.dto.OverlapActivitySummaryResponse;
import com.cadence.api.activities.dto.OverlapPairResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.CursorPage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Service for {@code GET /v1/activities/overlaps} - see {@link ActivityOverlapRepository} for
 * the query itself and the design rationale. */
@Service
public class ActivityOverlapService {

	private static final Set<String> VALID_OTHER_HAS = Set.of("power", "heartrate", "cadence", "core_temp");
	private static final double DEFAULT_MIN_OVERLAP_PCT = 50.0;
	/** Issue #541: a side's "pedalling at 0 W" seconds must reach this share of its duration
	 * before avgPowerRatio is flagged as possibly dropout-affected. Kept in sync with backend's
	 * activities/overlaps.py. */
	static final double DROPOUT_AFFECTED_MIN_SHARE = 0.005;

	private final ActivityOverlapRepository overlapRepository;

	public ActivityOverlapService(ActivityOverlapRepository overlapRepository) {
		this.overlapRepository = overlapRepository;
	}

	public CursorPage<OverlapPairResponse> findOverlaps(
			String athleteId, String activityId, Sport sport, boolean crossSport, String device, String otherDevice,
			Environment environment, LocalDate from, LocalDate to, Integer minDurationSeconds,
			List<String> otherHas, Double minOverlapPct, Long maxStartDeltaSeconds, boolean includeResolved,
			String cursor, int limit) {
		for (String has : otherHas) {
			if (!VALID_OTHER_HAS.contains(has)) {
				throw new ValidationException(
						"otherHas must be one of " + VALID_OTHER_HAS + ", got \"" + has + "\".", "otherHas");
			}
		}
		Instant fromInstant = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
		// Inclusive of `to` itself - one day past midnight makes the upper bound exclusive.
		Instant toInstantExclusive = to != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;
		CursorValue decodedCursor = decodeCursor(cursor);

		List<ActivityOverlapRepository.OverlapRow> rows = overlapRepository.findOverlaps(
				athleteId,
				activityId,
				sport != null ? sport.wireValue() : null,
				crossSport,
				device,
				otherDevice,
				environment != null ? environment.wireValue() : null,
				fromInstant,
				toInstantExclusive,
				minDurationSeconds,
				otherHas.contains("power"),
				otherHas.contains("heartrate"),
				otherHas.contains("cadence"),
				otherHas.contains("core_temp"),
				includeResolved,
				minOverlapPct != null ? minOverlapPct : DEFAULT_MIN_OVERLAP_PCT,
				maxStartDeltaSeconds,
				decodedCursor != null ? decodedCursor.anchorStartDate() : null,
				decodedCursor != null ? decodedCursor.anchorId() : null,
				decodedCursor != null ? decodedCursor.otherId() : null,
				limit + 1);

		boolean hasMore = rows.size() > limit;
		List<ActivityOverlapRepository.OverlapRow> page = hasMore ? rows.subList(0, limit) : rows;
		Map<String, Long> suspectSeconds = loadSuspectSeconds(page);
		List<OverlapPairResponse> data = new ArrayList<>(page.size());
		for (ActivityOverlapRepository.OverlapRow row : page) {
			data.add(toResponse(row, suspectSeconds));
		}
		String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;
		return new CursorPage<>(hasMore, nextCursor, data);
	}

	/** Only for pairs that actually have a ratio to flag, and only for the returned page. */
	private Map<String, Long> loadSuspectSeconds(List<ActivityOverlapRepository.OverlapRow> page) {
		Set<String> ids = new HashSet<>();
		for (ActivityOverlapRepository.OverlapRow row : page) {
			if (row.getAvgPowerRatio() != null) {
				ids.add(row.getAnchorId());
				ids.add(row.getOtherId());
			}
		}
		Map<String, Long> counts = new HashMap<>();
		if (ids.isEmpty()) {
			return counts;
		}
		for (ActivityOverlapRepository.PedallingZeroCount c : overlapRepository.countPedallingZeroSeconds(ids)) {
			counts.put(c.getActivityId(), c.getSuspectSeconds());
		}
		return counts;
	}

	private static boolean sideDropoutAffected(Map<String, Long> suspectSeconds, String id, Integer durationSeconds) {
		long suspect = suspectSeconds.getOrDefault(id, 0L);
		return suspect > 0 && durationSeconds != null && durationSeconds > 0
				&& (double) suspect / durationSeconds >= DROPOUT_AFFECTED_MIN_SHARE;
	}

	private static OverlapPairResponse toResponse(
			ActivityOverlapRepository.OverlapRow row, Map<String, Long> suspectSeconds) {
		OverlapActivitySummaryResponse anchor = new OverlapActivitySummaryResponse(
				row.getAnchorId(), row.getAnchorName(), Sport.fromWireValue(row.getAnchorSport()), row.getAnchorDevice(),
				row.getAnchorStartDate(), row.getAnchorElapsedSeconds(), row.getAnchorAvgPower());
		OverlapActivitySummaryResponse other = new OverlapActivitySummaryResponse(
				row.getOtherId(), row.getOtherName(), Sport.fromWireValue(row.getOtherSport()), row.getOtherDevice(),
				row.getOtherStartDate(), row.getOtherElapsedSeconds(), row.getOtherAvgPower());
		Boolean dropoutAffected = row.getAvgPowerRatio() == null ? null
				: sideDropoutAffected(suspectSeconds, row.getAnchorId(), row.getAnchorElapsedSeconds())
						|| sideDropoutAffected(suspectSeconds, row.getOtherId(), row.getOtherElapsedSeconds());
		return new OverlapPairResponse(anchor, other, row.getOverlapSeconds(), row.getOverlapPct(),
				row.getStartDeltaSeconds(), row.getDurationDeltaPct(), row.getAvgPowerRatio(), dropoutAffected,
				row.getStatus());
	}

	private record CursorValue(Instant anchorStartDate, String anchorId, String otherId) {
	}

	private static String encodeCursor(ActivityOverlapRepository.OverlapRow lastRow) {
		String raw = lastRow.getAnchorStartDate().toEpochMilli() + ":" + lastRow.getAnchorId() + ":" + lastRow.getOtherId();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
	}

	private static CursorValue decodeCursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			String[] parts = new String(Base64.getUrlDecoder().decode(cursor)).split(":", 3);
			return new CursorValue(Instant.ofEpochMilli(Long.parseLong(parts[0])), parts[1], parts[2]);
		}
		catch (RuntimeException e) {
			throw new ValidationException("Invalid cursor.", "cursor");
		}
	}
}
