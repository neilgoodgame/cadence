package com.cadence.api.athletes;

import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.athletes.dto.BestEffortListResponse;
import com.cadence.api.athletes.dto.BestEffortResponse;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BestEffortController {

	private final BestEffortRepository bestEffortRepository;
	private final AccessGuard accessGuard;

	public BestEffortController(BestEffortRepository bestEffortRepository, AccessGuard accessGuard) {
		this.bestEffortRepository = bestEffortRepository;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/athletes/{id}/best-efforts")
	public BestEffortListResponse listBestEfforts(@PathVariable String id,
			@RequestParam BestEffortKind kind,
			@RequestParam(defaultValue = "all") String period) {
		accessGuard.requireRead(id);
		LocalDate since = switch (period) {
			case "3m" -> LocalDate.now().minusDays(90);
			case "1y" -> LocalDate.now().minusDays(365);
			default -> null;
		};
		List<BestEffort> efforts = since != null
				? bestEffortRepository.findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindow(id, kind, since)
				: bestEffortRepository.findByAthleteIdAndKindOrderByWindow(id, kind);
		List<BestEffortResponse> data = efforts.stream()
				.map(e -> new BestEffortResponse(e.getWindow(), e.getValue(), e.getUnit(), e.getDate(), e.getActivity().getId()))
				.toList();
		return new BestEffortListResponse(kind, period, data);
	}
}
