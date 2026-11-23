package com.cadence.api.activities;

import com.cadence.api.activities.calc.StreamComparisonCalculator;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Issue #540 (phase 5): agreement trends over time - per-activity agreement statistics
 * (bias/MAE/RMSE/Pearson r/tolerance/lag/dropouts) between two device-class tags for one field,
 * across an athlete's activities in a date range. A distinct orchestration (iterate activities,
 * skip/aggregate) over {@link ActivitySourceService}'s per-activity building blocks - see that
 * class for tag resolution and {@link StreamComparisonCalculator} for the pure statistics.
 * Mirrors backend's activities/agreement_trend_service.py - keep the two in sync when changing
 * either.
 *
 * <p>Per-activity cost is non-trivial (each activity's full Record set or ActivityStream values
 * array is loaded, then comparison + lag (+ dropout) math runs over it) - the same shape of
 * acceptable cost FitnessService's per-activity aggregations already accept: "acceptable for a
 * calibration tool queried occasionally over a bounded window, not a high-frequency dashboard
 * path - revisit if that ever changes."
 */
@Service
public class AgreementTrendService {

	private static final List<String> VALID_FIELDS = List.of("power", "heartrate", "cadence");

	// Device-class tag vocabulary per field - the tags device_a/device_b are validated against.
	// power's is sport-dependent (bike vs run), which is why field="power" requires a sport:
	// there's no sensible single vocabulary otherwise, and mixing bike/run power into one trend is
	// meaningless anyway. Keyed by sport for the two power vocabularies, by field otherwise -
	// lookups below never iterate this map, so Map.of's unspecified order is irrelevant.
	private static final Map<String, List<String>> DEVICE_TAG_VOCABULARY = Map.of(
			"bike", List.of("trainer", "pedals"), "run", List.of("native", "stryd"),
			"heartrate", List.of("strap", "wrist"), "cadence", List.of("pedals", "trainer", "watch"));

	// The real training sports an optional sport filter may name for heartrate/cadence - same list
	// FitnessTrendTools' get_fitness_trend validates against.
	private static final List<String> VALID_FILTER_SPORTS = List.of("bike", "run", "swim", "walk", "row");

	private final ActivityRepository activityRepository;
	private final ActivitySourceService sourceService;

	public AgreementTrendService(ActivityRepository activityRepository, ActivitySourceService sourceService) {
		this.activityRepository = activityRepository;
		this.sourceService = sourceService;
	}

	public record AgreementTrendPoint(String activityId, String name, LocalDate date, int overlapSeconds,
			double bias, double mae, double rmse, double pearsonR, double withinTolerancePct,
			Integer lagSeconds, int dropoutSecondsA, int dropoutSecondsB) {
	}

	public record AgreementTrendResult(List<AgreementTrendPoint> points, Map<String, Integer> skipped) {
	}

	public record AgreementTrendResponse(String field, String deviceA, String deviceB, String sport,
			LocalDate fromDate, LocalDate toDate, List<AgreementTrendPoint> points, Map<String, Integer> skipped) {
	}

	private static void validateDeviceTags(String field, String deviceA, String deviceB, String sport) {
		// field reuses VALID_FIELDS' own check at the caller; this validates device_a/device_b
		// against field's vocabulary (sport-dependent for power) and the sport param itself. Only
		// a bad combination raises - "not enough data on this one activity" is never an error
		// here, that activity is just skipped and tallied.
		List<String> vocabulary;
		if (field.equals("power")) {
			if (!Objects.equals(sport, "bike") && !Objects.equals(sport, "run")) {
				throw new ValidationException("sport is required when field is 'power' (must be 'bike' or 'run').",
						"sport");
			}
			vocabulary = DEVICE_TAG_VOCABULARY.get(sport);
		}
		else {
			if (sport != null && !VALID_FILTER_SPORTS.contains(sport)) {
				throw new ValidationException("sport must be one of bike, run, swim, walk, row.", "sport");
			}
			vocabulary = DEVICE_TAG_VOCABULARY.get(field);
		}
		for (String[] paramTag : new String[][] { { "device_a", deviceA }, { "device_b", deviceB } }) {
			// A missing param arrives as null (the REST view/MCP tool pass it through rather
			// than defaulting to "") - null is simply not in the vocabulary, same 400 as an
			// empty string would be.
			if (paramTag[1] == null || !vocabulary.contains(paramTag[1])) {
				throw new ValidationException(
						paramTag[0] + " must be one of " + String.join(", ", vocabulary) + " for field '" + field
								+ "'.",
						paramTag[0]);
			}
		}
		if (Objects.equals(deviceA, deviceB)) {
			throw new ValidationException("device_a and device_b must be two different device tags.", "device_b");
		}
	}

	/** Issue #540 (phase 5). Iterates the athlete's activities in [fromDate, toDate]
	 * (Activity.sport filter applied if given; multisport children and - unless includeDuplicates,
	 * mirroring the fitness trend's own flag - same-ride-twice duplicate copies excluded, the
	 * same exclusion every other multi-activity aggregation in this codebase makes), resolves
	 * device_a/device_b each to a series via {@link ActivitySourceService#resolveTaggedSeries},
	 * and reuses {@link StreamComparisonCalculator#compare} (same tolerance rule compareStreams
	 * uses) plus findLag and - for field="power" - the same dropout detection compareStreams
	 * runs, per activity. Returns the points plus a skip-reason tally. Never raises for "not
	 * enough data on this one activity" - that activity is just skipped and counted; only a bad
	 * field/device_a/device_b/sport combination is a 400.
	 *
	 * <p>Deliberately NOT {@code @Transactional}: the loop only reads simple entity columns and
	 * issues fresh repository calls (each its own short read-only transaction), so wrapping the
	 * whole multi-activity sweep in one long transaction would just pin a connection and keep
	 * every loaded entity managed for the range's full size - the Python twin has no such
	 * transaction, and FitnessService's same-shaped per-activity aggregations aren't either. */
	public AgreementTrendResult computeAgreementTrend(String athleteId, String field, String deviceA,
			String deviceB, String sport, LocalDate fromDate, LocalDate toDate, boolean excludeDropouts,
			boolean includeDuplicates) {
		if (field == null || !VALID_FIELDS.contains(field)) {
			throw new ValidationException("field must be 'power', 'heartrate', or 'cadence'.", "field");
		}
		validateDeviceTags(field, deviceA, deviceB, sport);

		Sport effectiveSport = sport == null ? null : Sport.fromWireValue(sport);
		Instant start = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities = activityRepository.findForAgreementTrend(athleteId, effectiveSport, start, end,
				includeDuplicates);

		StreamComparisonCalculator.Tolerance tolerance = field.equals("heartrate")
				? StreamComparisonCalculator.Tolerance.absolute(5.0)
				: StreamComparisonCalculator.Tolerance.relativePct(3.0);

		List<AgreementTrendPoint> points = new ArrayList<>();
		Map<String, Integer> skipped = new LinkedHashMap<>();
		skipped.put("no_device_a", 0);
		skipped.put("no_device_b", 0);
		skipped.put("insufficient_overlap", 0);

		for (Activity activity : activities) {
			// Load this activity's stored sources + streams once so both device tags resolve in
			// memory (mirrors backend's prefetched sources__streams) instead of re-querying per tag.
			ActivitySourceService.TaggedSeriesLookup lookup = sourceService.loadTaggedSeriesLookup(activity);
			Map<Integer, Integer> seriesA = sourceService.resolveTaggedSeries(activity, field, deviceA, lookup);
			if (seriesA == null) {
				skipped.merge("no_device_a", 1, Integer::sum);
				continue;
			}
			Map<Integer, Integer> seriesB = sourceService.resolveTaggedSeries(activity, field, deviceB, lookup);
			if (seriesB == null) {
				skipped.merge("no_device_b", 1, Integer::sum);
				continue;
			}

			Map<Integer, Integer> compareSeriesA = seriesA;
			Map<Integer, Integer> compareSeriesB = seriesB;
			int dropoutSecondsA = 0;
			int dropoutSecondsB = 0;
			if (field.equals("power")) {
				ActivitySourceService.PowerDropoutExclusion exclusion = sourceService.applyPowerDropoutExclusion(activity,
						seriesA, seriesB, excludeDropouts);
				compareSeriesA = exclusion.compareSeriesA();
				compareSeriesB = exclusion.compareSeriesB();
				dropoutSecondsA = exclusion.dropoutSecondsA().size();
				dropoutSecondsB = exclusion.dropoutSecondsB().size();
			}

			StreamComparisonCalculator.ComparisonResult comparison = StreamComparisonCalculator.compare(compareSeriesA,
					compareSeriesB, tolerance);
			if (comparison == null) {
				skipped.merge("insufficient_overlap", 1, Integer::sum);
				continue;
			}
			StreamComparisonCalculator.LagResult lag = StreamComparisonCalculator.findLag(compareSeriesA, compareSeriesB);

			points.add(new AgreementTrendPoint(activity.getId(), activity.getName(),
					activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(), comparison.overlapSeconds(),
					comparison.bias(), comparison.mae(), comparison.rmse(), comparison.pearsonR(),
					comparison.withinTolerancePct(), lag == null ? null : lag.lagSeconds(), dropoutSecondsA,
					dropoutSecondsB));
		}

		return new AgreementTrendResult(points, skipped);
	}
}
