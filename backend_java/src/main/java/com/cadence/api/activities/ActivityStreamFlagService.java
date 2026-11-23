package com.cadence.api.activities;

import com.cadence.api.activities.calc.CadenceLockDetector;
import com.cadence.api.activities.calc.DropoutDetector;
import com.cadence.api.activities.calc.ImplausibleJumpDetector;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #540 (phase 6): GET .../streams/flags - orchestrates two different detectors behind one
 * read-only endpoint, since "detect contact-loss" and "detect cadence-lock" dispatch to entirely
 * different pure-computation classes (field="power" contact-loss delegates to {@link
 * DropoutDetector}, unchanged; heartrate/cadence contact-loss delegates to {@link
 * ImplausibleJumpDetector}; cadence-lock delegates to {@link CadenceLockDetector}), with one
 * consistent request/response shape. That's why this lives in its own service rather than
 * extending either detector or {@link ActivitySourceService} itself. Mirrors backend's
 * activities/stream_flag_service.py - keep the two in sync when changing either.
 *
 * <p>Never persists - flags are always recomputed fresh, same stance {@link DropoutDetector}'s
 * existing call sites already take. See {@link ActivityStreamPatchService} for the write side
 * (POST streams/patch/unpatch), which calls back into this service for its own
 * {@code segments="flagged"} case.
 */
@Service
public class ActivityStreamFlagService {

	public enum Kind {
		CONTACT_LOSS, CADENCE_LOCK;

		@JsonValue
		public String wireValue() {
			return name().toLowerCase(Locale.ROOT);
		}

		@JsonCreator
		public static Kind fromWireValue(String value) {
			return Kind.valueOf(value.toUpperCase(Locale.ROOT));
		}
	}

	/** One flagged [startT, endT] inclusive span, normalized from whichever detector produced it
	 * - DropoutRun/ImplausibleRun carry startT+length, LockRun already carries startT+endT; this
	 * is the one shape {@link ActivityStreamPatchService} and the REST response both deal in.
	 * referenceConfirmed is always true for CONTACT_LOSS (both of its detectors already filter
	 * out everything they can't confirm before returning); for CADENCE_LOCK it reflects whether
	 * that specific run had a corroborating referenceHr. */
	public record FlagSegment(int startT, int endT, boolean referenceConfirmed) {
	}

	public record StreamFlagsResult(String field, Kind kind, List<FlagSegment> segments) {
	}

	private final ActivityRepository activityRepository;
	private final ActivitySourceService activitySourceService;

	public ActivityStreamFlagService(ActivityRepository activityRepository, ActivitySourceService activitySourceService) {
		this.activityRepository = activityRepository;
		this.activitySourceService = activitySourceService;
	}

	/** kind=CONTACT_LOSS: field may be "power"/"heartrate"/"cadence". kind=CADENCE_LOCK: field
	 * must be "heartrate". reference, when given, is a source ref ("primary" or a real
	 * ActivitySource id, same shape as compareStreams' a/b) - the corroborating stream; omitting
	 * it is allowed (weaker evidence - see each detector's own "no reference" behavior). */
	@Transactional(readOnly = true)
	public StreamFlagsResult detectStreamFlags(String activityId, String field, Kind kind, String reference) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		List<FlagSegment> segments = kind == Kind.CONTACT_LOSS
				? contactLossSegments(activity, field, reference)
				: cadenceLockSegments(activity, field, reference);
		return new StreamFlagsResult(field, kind, segments);
	}

	private List<FlagSegment> contactLossSegments(Activity activity, String field, String reference) {
		ActivitySourceService.validateField(field);
		Map<Integer, Integer> primary = activitySourceService.loadFieldSeries(activity, field,
				ActivitySourceService.PRIMARY_SOURCE_REF);
		Map<Integer, Integer> referenceSeries = reference == null ? null
				: activitySourceService.loadFieldSeries(activity, field, reference);

		if (field.equals("power")) {
			// Same single-stream fallback ActivitySourceService.detectPowerDropouts already uses:
			// cadence is only loaded when there's no reference to confirm against.
			Map<Integer, Integer> cadenceSeries = referenceSeries == null
					? activitySourceService.loadFieldSeries(activity, "cadence", ActivitySourceService.PRIMARY_SOURCE_REF)
					: null;
			DropoutDetector.DropoutResult result = DropoutDetector.detectDropouts(primary, referenceSeries, cadenceSeries);
			List<FlagSegment> segments = new ArrayList<>();
			for (DropoutDetector.DropoutRun run : result.runs()) {
				segments.add(new FlagSegment(run.startT(), run.startT() + run.length() - 1, true));
			}
			return segments;
		}

		ImplausibleJumpDetector.ImplausibleJumpResult result = ImplausibleJumpDetector.detectImplausibleJumps(primary,
				field, referenceSeries);
		List<FlagSegment> segments = new ArrayList<>();
		for (ImplausibleJumpDetector.ImplausibleRun run : result.runs()) {
			segments.add(new FlagSegment(run.startT(), run.startT() + run.length() - 1, true));
		}
		return segments;
	}

	private List<FlagSegment> cadenceLockSegments(Activity activity, String field, String reference) {
		if (!field.equals("heartrate")) {
			throw new ValidationException("kind=cadence_lock only supports field='heartrate'.", "field");
		}
		Map<Integer, Integer> hrPrimary = activitySourceService.loadFieldSeries(activity, "heartrate",
				ActivitySourceService.PRIMARY_SOURCE_REF);
		Map<Integer, Integer> cadence = activitySourceService.loadFieldSeries(activity, "cadence",
				ActivitySourceService.PRIMARY_SOURCE_REF);
		Map<Integer, Integer> referenceHr = reference == null ? null
				: activitySourceService.loadFieldSeries(activity, "heartrate", reference);

		List<CadenceLockDetector.LockRun> lockRuns = CadenceLockDetector.detectCadenceLock(hrPrimary, cadence, referenceHr);
		List<FlagSegment> segments = new ArrayList<>();
		for (CadenceLockDetector.LockRun run : lockRuns) {
			segments.add(new FlagSegment(run.startT(), run.endT(), run.referenceCorrelation() != null));
		}
		return segments;
	}
}
