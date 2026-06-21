package com.cadence.api.athletes;

import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AthleteService {

	private final UserRepository userRepository;
	private final ZoneService zoneService;

	public AthleteService(UserRepository userRepository, ZoneService zoneService) {
		this.userRepository = userRepository;
		this.zoneService = zoneService;
	}

	/** Applies the patch and returns the zone types to report as recomputed - see {@link ZoneService#recomputedZoneTypes}. */
	@Transactional
	public List<ZoneType> updateProfile(User athlete, AthleteUpdateRequest request) {
		Set<String> changed = new HashSet<>();
		if (request.name() != null) {
			athlete.setName(request.name());
			changed.add("name");
		}
		if (request.age() != null) {
			athlete.setAge(request.age());
			changed.add("age");
		}
		if (request.ftp() != null) {
			athlete.setFtp(request.ftp());
			changed.add("ftp");
		}
		if (request.criticalRunPower() != null) {
			athlete.setCriticalRunPower(request.criticalRunPower());
			changed.add("criticalRunPower");
		}
		if (request.thresholdPace() != null) {
			athlete.setThresholdPace(request.thresholdPace());
			changed.add("thresholdPace");
		}
		if (request.lthr() != null) {
			athlete.setLthr(request.lthr());
			changed.add("lthr");
		}
		if (request.maxHr() != null) {
			athlete.setMaxHr(request.maxHr());
			changed.add("maxHr");
		}
		userRepository.save(athlete);
		return zoneService.recomputedZoneTypes(athlete, changed);
	}
}
