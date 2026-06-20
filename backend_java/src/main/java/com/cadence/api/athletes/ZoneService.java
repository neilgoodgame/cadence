package com.cadence.api.athletes;

import com.cadence.api.users.User;
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

	private final ZoneSetRepository zoneSetRepository;

	public ZoneService(ZoneSetRepository zoneSetRepository) {
		this.zoneSetRepository = zoneSetRepository;
	}

	public ZoneSet getOrCreate(User athlete, ZoneType type) {
		return zoneSetRepository.findByAthleteIdAndType(athlete.getId(), type)
				.orElseGet(() -> {
					ZoneSet zoneSet = new ZoneSet();
					zoneSet.setAthlete(athlete);
					zoneSet.setType(type);
					zoneSet.setZones(DEFAULT_ZONES);
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
