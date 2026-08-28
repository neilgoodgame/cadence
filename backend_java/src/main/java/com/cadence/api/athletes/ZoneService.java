package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.users.User;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four zone sets (heart rate, bike power, run power, pace), each a 5-band %-of-threshold
 * table created lazily on first access. Boundaries are stored; the reference threshold itself
 * is never stored - it's read live off the athlete's profile, since it moves whenever the
 * athlete's thresholds change.
 */
@Service
public class ZoneService {

	public static final List<Zone> DEFAULT_ZONES = List.of(
			new Zone("Z1 Recovery", 0, 55),
			new Zone("Z2 Endurance", 56, 75),
			new Zone("Z3 Tempo", 76, 90),
			new Zone("Z4 Threshold", 91, 105),
			new Zone("Z5 VO2max", 106, 150));

	/** Jack Daniels' five training paces (Daniels' Running Formula), not the generic
	 * power/HR table above - reusing that table for pace via ZoneRange's reciprocal transform
	 * (see the frontend's lib/zones.ts) stretches disproportionately at the easy end, since
	 * inverting a % is nonlinear. Percentages here are %-of-threshold-pace *effort* (matching
	 * every other zone type's "higher % = harder" convention - a real pace comes from
	 * reference / (pct / 100)), calibrated from Daniels' published VDOT tables so a runner's own
	 * Threshold pace lands inside the Threshold band, not at one of its edges. Daniels' own
	 * Threshold pace is itself defined as roughly a 60-minute effort - the same window this app's
	 * own threshold_pace/critical_run_power already use, so the mapping is a natural fit, not an
	 * approximation layered on top of an unrelated definition. */
	public static final List<Zone> DEFAULT_PACE_ZONES = List.of(
			new Zone("Easy", 0, 83),
			new Zone("Marathon", 84, 93),
			new Zone("Threshold", 94, 101),
			new Zone("Interval", 102, 109),
			new Zone("Repetition", 110, 150));

	private final ZoneSetRepository zoneSetRepository;
	private final ThresholdHistoryRepository thresholdHistoryRepository;

	public ZoneService(ZoneSetRepository zoneSetRepository, ThresholdHistoryRepository thresholdHistoryRepository) {
		this.zoneSetRepository = zoneSetRepository;
		this.thresholdHistoryRepository = thresholdHistoryRepository;
	}

	public ZoneSet getOrCreate(User athlete, ZoneType type) {
		return zoneSetRepository.findByAthleteIdAndType(athlete.getId(), type)
				.orElseGet(() -> {
					ZoneSet zoneSet = new ZoneSet();
					zoneSet.setAthlete(athlete);
					zoneSet.setType(type);
					zoneSet.setZones(type == ZoneType.PACE ? DEFAULT_PACE_ZONES : DEFAULT_ZONES);
					return zoneSetRepository.save(zoneSet);
				});
	}

	public List<ZoneSet> getAllOrCreate(User athlete) {
		return Arrays.stream(ZoneType.values())
				.map(type -> getOrCreate(athlete, type))
				.toList();
	}

	@Transactional
	public ZoneSet replaceZones(User athlete, ZoneType type, List<Zone> zones) {
		ZoneSet zoneSet = getOrCreate(athlete, type);
		zoneSet.setZones(zones);
		return zoneSetRepository.save(zoneSet);
	}

	/** The threshold value a zone type's percentages are relative to, computed live from the athlete's profile. */
	public Double referenceFor(User athlete, ZoneType type) {
		return switch (type) {
			case HEART_RATE -> athlete.getLthr() != null ? athlete.getLthr().doubleValue() : null;
			case BIKE_POWER -> athlete.getFtp() != null ? athlete.getFtp().doubleValue() : null;
			case RUN_POWER -> athlete.getCriticalRunPower() != null ? athlete.getCriticalRunPower().doubleValue() : null;
			case PACE -> mmssToSeconds(athlete.getThresholdPace());
		};
	}

	/** As {@link #referenceFor(User, ZoneType)}, but for BIKE_POWER/RUN_POWER/PACE looks up the
	 * ThresholdHistory ledger entry that was actually the *recorded current value* as of
	 * {@code activity}'s own date - filtered on currentFrom, not effectiveFrom - instead of the
	 * athlete's current profile, so a historic activity's zones stay pinned to what was true when
	 * it happened rather than moving every time the athlete's current profile changes. Filtering
	 * on effectiveFrom instead would let a row match its own activity's date even when it hadn't
	 * actually become current yet (see ThresholdHistory.getCurrentFrom()'s Javadoc for the
	 * cascading-expiry case where that happens for real). Returns null (not a fallback to the
	 * live profile) when no ledger entry is current yet - "unknown" is the correct answer, not a
	 * guess. HEART_RATE has no ledger of its own (lthr isn't rolling-window derived) and always
	 * reads live, same as the 2-arg overload. */
	public Double referenceFor(User athlete, ZoneType type, Activity activity) {
		if (activity == null || type == ZoneType.HEART_RATE) {
			return referenceFor(athlete, type);
		}
		ThresholdField field = switch (type) {
			case BIKE_POWER -> ThresholdField.FTP;
			case RUN_POWER -> ThresholdField.CRITICAL_RUN_POWER;
			case PACE -> ThresholdField.THRESHOLD_PACE;
			case HEART_RATE -> throw new IllegalStateException("unreachable");
		};
		LocalDate asOf = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
		return thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCurrentFromLessThanEqualOrderByCurrentFromDescIdDesc(athlete.getId(), field, asOf)
				.map(entry -> field == ThresholdField.THRESHOLD_PACE ? mmssToSeconds(entry.getValuePace())
						: entry.getValueNumeric().doubleValue())
				.orElse(null);
	}

	/**
	 * Given the athlete profile fields that just changed, returns the zone types to report as
	 * "recomputed" - restricted to ones that already have a {@code ZoneSet} row for this
	 * athlete. A zone type with no row yet has nothing to recompute: it gets created fresh
	 * (with default boundaries) on first access regardless, already reading the new threshold
	 * live via {@link #referenceFor}, so reporting it here would overstate what changed.
	 */
	public List<ZoneType> recomputedZoneTypes(User athlete, Set<String> changedFields) {
		return zoneTypesAffectedBy(changedFields).stream()
				.filter(type -> zoneSetRepository.existsByAthleteIdAndType(athlete.getId(), type))
				.toList();
	}

	/** Given the athlete profile fields that changed, returns the zone types whose reference threshold depends on one of them. */
	public List<ZoneType> zoneTypesAffectedBy(Set<String> changedFields) {
		List<ZoneType> affected = new ArrayList<>();
		if (changedFields.contains("lthr")) {
			affected.add(ZoneType.HEART_RATE);
		}
		if (changedFields.contains("ftp")) {
			affected.add(ZoneType.BIKE_POWER);
		}
		if (changedFields.contains("criticalRunPower")) {
			affected.add(ZoneType.RUN_POWER);
		}
		if (changedFields.contains("thresholdPace")) {
			affected.add(ZoneType.PACE);
		}
		return affected;
	}

	private static Double mmssToSeconds(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String[] parts = value.split(":");
		if (parts.length != 2) {
			return null;
		}
		try {
			int minutes = Integer.parseInt(parts[0]);
			int seconds = Integer.parseInt(parts[1]);
			return (double) (minutes * 60 + seconds);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}
}
