package com.cadence.api.garmin;

import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.garmin.dto.GarminWorkoutLinksResponse;
import com.cadence.api.garmin.dto.GarminWorkoutOpRequest;
import com.cadence.api.garmin.dto.GarminWorkoutOpResponse;
import com.cadence.api.garmin.dto.GarminWorkoutOpResultRequest;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.users.User;
import com.cadence.api.workouts.WorkoutRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox behind "push to Garmin Connect". The browser queues operations here; a bridge on the
 * athlete's own machine (using the athlete's own Garmin login, which Cadence never sees) fetches
 * the pending ones, applies them in order and reports back the Garmin ids, which are kept as links
 * so later edits, reschedules and cancels target the right Garmin objects. Operations on the same
 * workout or scheduled workout that are still pending are merged rather than queued twice.
 */
@Service
public class GarminWorkoutPushService {

	/** Finished (done or failed) operations kept per athlete. */
	static final int FINISHED_KEPT = 100;

	private final GarminWorkoutOpRepository ops;
	private final GarminWorkoutLinkRepository workoutLinks;
	private final GarminScheduleLinkRepository scheduleLinks;
	private final GarminConnectionRepository connections;
	private final WorkoutRepository workouts;
	private final ScheduledWorkoutRepository scheduled;

	public GarminWorkoutPushService(GarminWorkoutOpRepository ops, GarminWorkoutLinkRepository workoutLinks,
			GarminScheduleLinkRepository scheduleLinks, GarminConnectionRepository connections,
			WorkoutRepository workouts, ScheduledWorkoutRepository scheduled) {
		this.ops = ops;
		this.workoutLinks = workoutLinks;
		this.scheduleLinks = scheduleLinks;
		this.connections = connections;
		this.workouts = workouts;
		this.scheduled = scheduled;
	}

	/** Empty when there was nothing to do (e.g. cancelling something that never reached Garmin). */
	@Transactional
	public Optional<GarminWorkoutOp> enqueue(User owner, GarminWorkoutOpRequest request) {
		requireUsableConnection(owner);
		GarminWorkoutOpKind kind = request.kind();
		if (kind != GarminWorkoutOpKind.UNSCHEDULE) {
			requireVisibleWorkout(owner, request.workoutId());
		}
		return switch (kind) {
			case UPSERT_WORKOUT -> Optional.of(upsertWorkout(owner, request));
			case SCHEDULE, RESCHEDULE -> Optional.of(ensureScheduled(owner, request));
			case UNSCHEDULE -> unschedule(owner, request);
		};
	}

	@Transactional
	public GarminWorkoutOp recordResult(User owner, String opId, GarminWorkoutOpResultRequest result) {
		GarminWorkoutOp op = ops.findByIdAndOwnerId(opId, owner.getId())
				.orElseThrow(() -> new NotFoundException("No such operation."));
		if (op.getStatus() != GarminWorkoutOpStatus.PENDING) {
			throw new ConflictException("That operation has already been reported.");
		}
		GarminWorkoutOpStatus status = parseStatus(result.status());
		if (status == GarminWorkoutOpStatus.FAILED) {
			op.setError(result.error() == null || result.error().isBlank() ? "Failed." : result.error());
		}
		else {
			applyDone(owner, op, result);
		}
		op.setStatus(status);
		op.setCompletedAt(Instant.now());
		ops.save(op);
		pruneFinished(owner.getId());
		return op;
	}

	@Transactional
	public void discard(User owner, String opId) {
		GarminWorkoutOp op = ops.findByIdAndOwnerId(opId, owner.getId())
				.orElseThrow(() -> new NotFoundException("No such operation."));
		if (op.getStatus() == GarminWorkoutOpStatus.DONE) {
			throw new ConflictException("A completed operation cannot be discarded.");
		}
		ops.delete(op);
	}

	@Transactional(readOnly = true)
	public List<GarminWorkoutOpResponse> pending(String ownerId) {
		return ops.findByOwnerIdAndStatusOrderByCreatedAsc(ownerId, GarminWorkoutOpStatus.PENDING).stream()
				.map(op -> toResponse(ownerId, op)).toList();
	}

	@Transactional(readOnly = true)
	public List<GarminWorkoutOpResponse> failed(String ownerId) {
		return ops.findByOwnerIdAndStatusOrderByCreatedAsc(ownerId, GarminWorkoutOpStatus.FAILED).stream()
				.map(op -> toResponse(ownerId, op)).toList();
	}

	@Transactional(readOnly = true)
	public List<GarminWorkoutOpResponse> recent(String ownerId, int limit) {
		return ops.findByOwnerIdOrderByCreatedDesc(ownerId).stream().limit(limit)
				.map(op -> toResponse(ownerId, op)).toList();
	}

	@Transactional(readOnly = true)
	public GarminWorkoutLinksResponse links(String ownerId) {
		return new GarminWorkoutLinksResponse(
				workoutLinks.findByOwnerId(ownerId).stream().map(l -> new GarminWorkoutLinksResponse.WorkoutLink(
						l.getWorkoutId(), l.getGarminWorkoutId(), l.getPushedAt())).toList(),
				scheduleLinks.findByOwnerId(ownerId).stream().map(l -> new GarminWorkoutLinksResponse.ScheduleLink(
						l.getScheduledWorkoutId(), l.getGarminScheduleId(), l.getScheduledFor())).toList(),
				ops.countByOwnerIdAndStatus(ownerId, GarminWorkoutOpStatus.PENDING),
				ops.countByOwnerIdAndStatus(ownerId, GarminWorkoutOpStatus.FAILED));
	}

	public GarminWorkoutOpResponse toResponse(String ownerId, GarminWorkoutOp op) {
		Long garminWorkoutId = op.getGarminWorkoutId() != null ? op.getGarminWorkoutId()
				: workoutLinks.findByOwnerIdAndWorkoutId(ownerId, op.getWorkoutId())
						.map(GarminWorkoutLink::getGarminWorkoutId).orElse(null);
		Long garminScheduleId = op.getGarminScheduleId() != null ? op.getGarminScheduleId()
				: op.getScheduledWorkoutId() == null ? null
						: scheduleLinks.findByOwnerIdAndScheduledWorkoutId(ownerId, op.getScheduledWorkoutId())
								.map(GarminScheduleLink::getGarminScheduleId).orElse(null);
		return GarminWorkoutOpResponse.from(op, garminWorkoutId, garminScheduleId);
	}

	private GarminWorkoutOp upsertWorkout(User owner, GarminWorkoutOpRequest request) {
		if (request.tcx() == null || request.tcx().isBlank()) {
			throw new ValidationException("tcx is required to push a workout.", "tcx");
		}
		GarminWorkoutOp op = ops.findFirstByOwnerIdAndStatusAndKindAndWorkoutId(owner.getId(),
				GarminWorkoutOpStatus.PENDING, GarminWorkoutOpKind.UPSERT_WORKOUT, request.workoutId())
				.orElseGet(() -> newOp(owner, GarminWorkoutOpKind.UPSERT_WORKOUT, request.workoutId()));
		op.setTcx(request.tcx());
		op.setName(request.name());
		return ops.save(op);
	}

	/** SCHEDULE and RESCHEDULE both mean "this scheduled workout should be on Garmin on this date". */
	private GarminWorkoutOp ensureScheduled(User owner, GarminWorkoutOpRequest request) {
		requireScheduledFields(request);
		List<GarminWorkoutOp> pendingForSchedule = ops.findByOwnerIdAndStatusAndScheduledWorkoutId(owner.getId(),
				GarminWorkoutOpStatus.PENDING, request.scheduledWorkoutId());
		Optional<GarminWorkoutOp> existing = pendingForSchedule.stream()
				.filter(o -> o.getKind() == GarminWorkoutOpKind.SCHEDULE || o.getKind() == GarminWorkoutOpKind.RESCHEDULE)
				.findFirst();
		if (existing.isPresent()) {
			existing.get().setDate(request.date());
			return ops.save(existing.get());
		}
		boolean alreadyOnGarmin = scheduleLinks
				.findByOwnerIdAndScheduledWorkoutId(owner.getId(), request.scheduledWorkoutId()).isPresent();
		GarminWorkoutOp op = newOp(owner, alreadyOnGarmin ? GarminWorkoutOpKind.RESCHEDULE : GarminWorkoutOpKind.SCHEDULE,
				request.workoutId());
		op.setScheduledWorkoutId(request.scheduledWorkoutId());
		op.setDate(request.date());
		return ops.save(op);
	}

	private Optional<GarminWorkoutOp> unschedule(User owner, GarminWorkoutOpRequest request) {
		if (request.scheduledWorkoutId() == null || request.scheduledWorkoutId().isBlank()) {
			throw new ValidationException("scheduled_workout_id is required.", "scheduled_workout_id");
		}
		List<GarminWorkoutOp> pendingForSchedule = ops.findByOwnerIdAndStatusAndScheduledWorkoutId(owner.getId(),
				GarminWorkoutOpStatus.PENDING, request.scheduledWorkoutId());
		// A schedule that never reached Garmin is simply dropped.
		ops.deleteAll(pendingForSchedule.stream().filter(o -> o.getKind() != GarminWorkoutOpKind.UNSCHEDULE).toList());
		boolean onGarmin = scheduleLinks
				.findByOwnerIdAndScheduledWorkoutId(owner.getId(), request.scheduledWorkoutId()).isPresent();
		boolean alreadyQueued = pendingForSchedule.stream().anyMatch(o -> o.getKind() == GarminWorkoutOpKind.UNSCHEDULE);
		if (!onGarmin || alreadyQueued) {
			return Optional.empty();
		}
		GarminWorkoutOp op = newOp(owner, GarminWorkoutOpKind.UNSCHEDULE, request.workoutId());
		op.setScheduledWorkoutId(request.scheduledWorkoutId());
		return Optional.of(ops.save(op));
	}

	private void applyDone(User owner, GarminWorkoutOp op, GarminWorkoutOpResultRequest result) {
		switch (op.getKind()) {
			case UPSERT_WORKOUT -> {
				requireId(result.garminWorkoutId(), "garmin_workout_id");
				op.setGarminWorkoutId(result.garminWorkoutId());
				GarminWorkoutLink link = workoutLinks.findByOwnerIdAndWorkoutId(owner.getId(), op.getWorkoutId())
						.orElseGet(() -> new GarminWorkoutLink(owner, op.getWorkoutId(), result.garminWorkoutId()));
				link.setGarminWorkoutId(result.garminWorkoutId());
				workoutLinks.save(link);
			}
			case SCHEDULE, RESCHEDULE -> {
				requireId(result.garminScheduleId(), "garmin_schedule_id");
				long garminWorkoutId = result.garminWorkoutId() != null ? result.garminWorkoutId()
						: workoutLinks.findByOwnerIdAndWorkoutId(owner.getId(), op.getWorkoutId())
								.map(GarminWorkoutLink::getGarminWorkoutId)
								.orElseThrow(() -> new ValidationException("garmin_workout_id is required.", "garmin_workout_id"));
				op.setGarminWorkoutId(garminWorkoutId);
				op.setGarminScheduleId(result.garminScheduleId());
				GarminScheduleLink link = scheduleLinks
						.findByOwnerIdAndScheduledWorkoutId(owner.getId(), op.getScheduledWorkoutId())
						.orElseGet(() -> new GarminScheduleLink(owner, op.getScheduledWorkoutId(),
								result.garminScheduleId(), garminWorkoutId, op.getDate()));
				link.update(result.garminScheduleId(), garminWorkoutId, op.getDate());
				scheduleLinks.save(link);
			}
			case UNSCHEDULE -> scheduleLinks
					.findByOwnerIdAndScheduledWorkoutId(owner.getId(), op.getScheduledWorkoutId())
					.ifPresent(scheduleLinks::delete);
		}
	}

	private void pruneFinished(String ownerId) {
		List<GarminWorkoutOp> finished = ops.findByOwnerIdOrderByCreatedDesc(ownerId).stream()
				.filter(o -> o.getStatus() != GarminWorkoutOpStatus.PENDING).toList();
		if (finished.size() > FINISHED_KEPT) {
			ops.deleteAll(finished.subList(FINISHED_KEPT, finished.size()));
		}
	}

	private GarminWorkoutOp newOp(User owner, GarminWorkoutOpKind kind, String workoutId) {
		GarminWorkoutOp op = new GarminWorkoutOp();
		op.setOwner(owner);
		op.setKind(kind);
		op.setWorkoutId(workoutId);
		return op;
	}

	private void requireUsableConnection(User owner) {
		GarminConnection connection = connections.findByOwnerId(owner.getId()).orElseThrow(
				() -> new ConflictException("Set up your Garmin connection in Preferences first."));
		if (connection.getKeyExpiresOn() != null && connection.getKeyExpiresOn().isBefore(LocalDate.now(ZoneOffset.UTC))) {
			throw new ConflictException("Your Garmin API key has expired. Replace it in Preferences first.");
		}
	}

	private void requireVisibleWorkout(User owner, String workoutId) {
		boolean visible = workouts.findById(workoutId)
				.map(w -> w.getCreatedBy() != null && w.getCreatedBy().getId().equals(owner.getId())
						|| scheduled.existsByAthleteIdAndWorkoutId(owner.getId(), workoutId))
				.orElse(false);
		if (!visible) {
			throw new NotFoundException("No such workout.");
		}
	}

	private static void requireScheduledFields(GarminWorkoutOpRequest request) {
		if (request.scheduledWorkoutId() == null || request.scheduledWorkoutId().isBlank()) {
			throw new ValidationException("scheduled_workout_id is required.", "scheduled_workout_id");
		}
		if (request.date() == null) {
			throw new ValidationException("date is required.", "date");
		}
	}

	private static void requireId(Long id, String field) {
		if (id == null || id < 1) {
			throw new ValidationException(field + " is required.", field);
		}
	}

	private static GarminWorkoutOpStatus parseStatus(String status) {
		try {
			GarminWorkoutOpStatus parsed = GarminWorkoutOpStatus.valueOf(status.toUpperCase());
			if (parsed == GarminWorkoutOpStatus.PENDING) {
				throw new IllegalArgumentException();
			}
			return parsed;
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("status must be done or failed.", "status");
		}
	}
}
