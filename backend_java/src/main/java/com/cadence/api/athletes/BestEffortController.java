package com.cadence.api.athletes;

import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.activities.BestEffortRecomputeService;
import com.cadence.api.athletes.dto.BestEffortListResponse;
import com.cadence.api.athletes.dto.BestEffortResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class BestEffortController {

	private final BestEffortRepository bestEffortRepository;
	private final BestEffortRecomputeService recomputeService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final Executor taskExecutor;
	private final ObjectMapper objectMapper;

	public BestEffortController(BestEffortRepository bestEffortRepository,
			BestEffortRecomputeService recomputeService, UserService userService,
			AccessGuard accessGuard, Executor taskExecutor, ObjectMapper objectMapper) {
		this.bestEffortRepository = bestEffortRepository;
		this.recomputeService = recomputeService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.taskExecutor = taskExecutor;
		this.objectMapper = objectMapper;
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
				? bestEffortRepository.findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindowAscValueDesc(id, kind, since)
				: bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(id, kind);
		List<BestEffortResponse> data = efforts.stream()
				.map(e -> new BestEffortResponse(e.getWindow(), e.getValue(), e.getUnit(), e.getDate(), e.getActivity().getId()))
				.toList();
		return new BestEffortListResponse(kind, period, data);
	}

	@DeleteMapping("/v1/athletes/{id}/best-efforts/by-activity/{activityId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	public void excludeActivity(@PathVariable String id, @PathVariable String activityId,
			@RequestParam BestEffortKind kind) {
		accessGuard.requireRead(id);
		bestEffortRepository.deleteByAthleteIdAndKindAndActivityId(id, kind, activityId);
	}

	@PostMapping(value = "/v1/athletes/{id}/best-efforts/recompute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter recompute(@PathVariable String id,
			@RequestParam(required = false) BestEffortKind kind) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		SseEmitter emitter = new SseEmitter(600_000L);

		taskExecutor.execute(() -> {
			try {
				int processed = (kind != null)
						? recomputeService.recompute(athlete, kind, (current, total) -> sendProgress(emitter, current, total))
						: recomputeService.recomputeAll(athlete, (current, total) -> sendProgress(emitter, current, total));
				emitter.send(SseEmitter.event()
						.name("done")
						.data(objectMapper.writeValueAsString(Map.of("processed", processed))));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});

		return emitter;
	}

	private void sendProgress(SseEmitter emitter, int current, int total) {
		try {
			emitter.send(SseEmitter.event()
					.data(objectMapper.writeValueAsString(Map.of("current", current, "total", total))));
		} catch (IOException e) {
			// client disconnected — ignore, the emitter will complete with error naturally
		}
	}

	@PostMapping("/v1/athletes/{id}/best-efforts/trim")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void trim(@PathVariable String id) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		recomputeService.trimAll(athlete);
	}
}
