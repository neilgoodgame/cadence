package com.cadence.api.athletes;

import com.cadence.api.activities.DerivedStatsRecomputeService;
import com.cadence.api.activities.TssRecomputeService;
import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.athletes.dto.AthleteUpdateResponse;
import com.cadence.api.athletes.dto.ZoneSetReplaceRequest;
import com.cadence.api.athletes.dto.ZoneSetReplaceResponse;
import com.cadence.api.athletes.dto.ZoneSetResponse;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserMapper;
import com.cadence.api.users.UserService;
import com.cadence.api.users.dto.UserResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AthleteController {

	private final UserService userService;
	private final UserMapper userMapper;
	private final AthleteService athleteService;
	private final ZoneService zoneService;
	private final FitnessService fitnessService;
	private final TssRecomputeService tssRecomputeService;
	private final DerivedStatsRecomputeService derivedStatsRecomputeService;
	private final AccessGuard accessGuard;
	private final Executor taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public AthleteController(UserService userService, UserMapper userMapper, AthleteService athleteService,
			ZoneService zoneService, FitnessService fitnessService, TssRecomputeService tssRecomputeService,
			DerivedStatsRecomputeService derivedStatsRecomputeService, AccessGuard accessGuard) {
		this.userService = userService;
		this.userMapper = userMapper;
		this.athleteService = athleteService;
		this.zoneService = zoneService;
		this.fitnessService = fitnessService;
		this.tssRecomputeService = tssRecomputeService;
		this.derivedStatsRecomputeService = derivedStatsRecomputeService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/athletes/{id}")
	public UserResponse getAthlete(@PathVariable String id) {
		accessGuard.requireRead(id);
		return userMapper.toResponse(userService.getById(id));
	}

	@PatchMapping("/v1/athletes/{id}")
	public AthleteUpdateResponse updateAthlete(@PathVariable String id, @RequestBody AthleteUpdateRequest request) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		List<ZoneType> recomputed = athleteService.updateProfile(athlete, request);
		List<String> recomputedWire = recomputed.stream().map(ZoneType::wireValue).toList();
		return new AthleteUpdateResponse(userMapper.toResponse(athlete), recomputedWire);
	}

	@GetMapping("/v1/athletes/{id}/zones")
	public DataListResponse<ZoneSetResponse> listZones(@PathVariable String id) {
		accessGuard.requireRead(id);
		User athlete = userService.getById(id);
		List<ZoneSetResponse> zones = zoneService.getAllOrCreate(athlete).stream()
				.map(zs -> new ZoneSetResponse(zs.getType(), zoneService.referenceFor(athlete, zs.getType()), zs.getZones()))
				.toList();
		return new DataListResponse<>(zones);
	}

	@PutMapping("/v1/athletes/{id}/zones/{type}")
	public ZoneSetReplaceResponse replaceZoneSet(@PathVariable String id, @PathVariable ZoneType type,
			@Valid @RequestBody ZoneSetReplaceRequest request) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		zoneService.replaceZones(athlete, type, request.zones());
		Double reference = zoneService.referenceFor(athlete, type);
		return new ZoneSetReplaceResponse(type, reference, true);
	}

	@PostMapping("/v1/athletes/{id}/recompute-tss")
	public ResponseEntity<Map<String, Integer>> recomputeTss(@PathVariable String id) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		int updated = tssRecomputeService.recomputeForAthlete(athlete);
		return ResponseEntity.ok(Map.of("updated", updated));
	}

	@PostMapping(value = "/v1/athletes/{id}/recompute-stats", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter recomputeStats(@PathVariable String id) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		SseEmitter emitter = new SseEmitter(600_000L);

		taskExecutor.execute(() -> {
			try {
				int updated = derivedStatsRecomputeService.recomputeForAthlete(athlete,
						(current, total) -> sendProgress(emitter, current, total));
				emitter.send(SseEmitter.event().name("done").data("{\"updated\":" + updated + "}"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});

		return emitter;
	}

	private void sendProgress(SseEmitter emitter, int current, int total) {
		try {
			emitter.send(SseEmitter.event().data("{\"current\":" + current + ",\"total\":" + total + "}"));
		} catch (IOException e) {
			// client disconnected - ignore, the emitter will complete with error naturally
		}
	}

	@GetMapping("/v1/athletes/{id}/fitness")
	public DataListResponse<FitnessPoint> getFitnessTrend(@PathVariable String id,
			@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
		accessGuard.requireRead(id);
		LocalDate effectiveTo = to != null ? to : LocalDate.now();
		LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(84);
		return new DataListResponse<>(fitnessService.computeFitnessSeries(id, effectiveFrom, effectiveTo));
	}
}
