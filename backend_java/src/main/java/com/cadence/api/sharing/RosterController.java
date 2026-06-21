package com.cadence.api.sharing;

import com.cadence.api.athletes.ComplianceService;
import com.cadence.api.athletes.FitnessPoint;
import com.cadence.api.athletes.FitnessService;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.sharing.dto.CoachAthleteResponse;
import com.cadence.api.sharing.dto.RosterEntryResponse;
import com.cadence.api.users.User;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RosterController {

	private final SharingService sharingService;
	private final UserRelationshipRepository userRelationshipRepository;
	private final ComplianceService complianceService;
	private final FitnessService fitnessService;
	private final ScheduledWorkoutRepository scheduledWorkoutRepository;

	public RosterController(SharingService sharingService, UserRelationshipRepository userRelationshipRepository,
			ComplianceService complianceService, FitnessService fitnessService,
			ScheduledWorkoutRepository scheduledWorkoutRepository) {
		this.sharingService = sharingService;
		this.userRelationshipRepository = userRelationshipRepository;
		this.complianceService = complianceService;
		this.fitnessService = fitnessService;
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
	}

	@GetMapping("/v1/coach/athletes")
	public DataListResponse<RosterEntryResponse> listRoster() {
		String coachId = AuthContextHolder.get().sub();
		LocalDate today = LocalDate.now();
		List<RosterEntryResponse> entries = sharingService.listCoachingContexts(coachId).stream()
				.filter(r -> r.getRole() == ShareRole.COACH)
				.map(r -> {
					User athlete = r.getOwner();
					double compliance = complianceService.computeCompliance(athlete.getId(), today);
					FitnessPoint fitness = fitnessService.computeFitnessPoint(athlete.getId(), today);
					int flags = complianceService.computeFlags(athlete.getId(), today);
					return new RosterEntryResponse(athlete.getId(), athlete.getName(), compliance,
							(int) Math.round(fitness.tsb()), flags);
				})
				.toList();
		return new DataListResponse<>(entries);
	}

	@GetMapping("/v1/coach/athletes/{id}")
	public CoachAthleteResponse getCoachAthlete(@PathVariable String id) {
		String coachId = AuthContextHolder.get().sub();
		userRelationshipRepository.findByOwnerIdAndGranteeIdAndStatus(id, coachId, ShareStatus.ACTIVE)
				.filter(r -> r.getRole() == ShareRole.COACH)
				.orElseThrow(() -> new ForbiddenException("You do not coach this athlete."));

		LocalDate today = LocalDate.now();
		FitnessPoint fitness = fitnessService.computeFitnessPoint(id, today);
		String nextWorkout = scheduledWorkoutRepository.findUpcomingPlannedWithWorkout(id, today).stream()
				.findFirst()
				.map(s -> s.getWorkout().getName())
				.orElse(null);
		return new CoachAthleteResponse(id, (int) Math.round(fitness.ctl()), (int) Math.round(fitness.atl()),
				(int) Math.round(fitness.tsb()), nextWorkout);
	}
}
