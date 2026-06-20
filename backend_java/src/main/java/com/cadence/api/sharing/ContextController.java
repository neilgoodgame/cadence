package com.cadence.api.sharing;

import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.athletes.ComplianceService;
import com.cadence.api.athletes.FitnessPoint;
import com.cadence.api.athletes.FitnessService;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.sharing.dto.CoachedAthleteResponse;
import com.cadence.api.sharing.dto.ContextsResponse;
import com.cadence.api.sharing.dto.ShareResponse;
import com.cadence.api.users.User;
import com.cadence.api.users.UserMapper;
import com.cadence.api.users.UserService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContextController {

	private final UserService userService;
	private final UserMapper userMapper;
	private final SharingService sharingService;
	private final ComplianceService complianceService;
	private final FitnessService fitnessService;
	private final ActivityRepository activityRepository;

	public ContextController(UserService userService, UserMapper userMapper, SharingService sharingService,
			ComplianceService complianceService, FitnessService fitnessService, ActivityRepository activityRepository) {
		this.userService = userService;
		this.userMapper = userMapper;
		this.sharingService = sharingService;
		this.complianceService = complianceService;
		this.fitnessService = fitnessService;
		this.activityRepository = activityRepository;
	}

	@GetMapping("/v1/me/contexts")
	public ContextsResponse getContexts() {
		String userId = AuthContextHolder.get().sub();
		User self = userService.getById(userId);
		LocalDate today = LocalDate.now();

		List<CoachedAthleteResponse> coaching = sharingService.listCoachingContexts(userId).stream()
				.map(r -> {
					User athlete = r.getOwner();
					double compliance = complianceService.computeCompliance(athlete.getId(), today);
					FitnessPoint fitness = fitnessService.computeFitnessPoint(athlete.getId(), today);
					var lastActivity = activityRepository.findLatestStartDate(athlete.getId()).orElse(null);
					return new CoachedAthleteResponse(r.getId(), athlete.getId(), athlete.getName(), r.getRole(),
							compliance, (int) Math.round(fitness.tsb()), lastActivity);
				})
				.toList();

		List<ShareResponse> coachedBy = sharingService.listSharesGrantedBy(userId).stream()
				.map(sharingService::toResponse)
				.toList();

		return new ContextsResponse(userMapper.toResponse(self), coaching, coachedBy);
	}
}
