package com.cadence.api.athletes;

import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.activities.BestEffortRecomputeService;
import com.cadence.api.athletes.dto.BestEffortListResponse;
import com.cadence.api.athletes.dto.BestEffortResponse;
import com.cadence.api.athletes.dto.RecentTopEffortResponse;
import com.cadence.api.athletes.dto.RecentTopEffortsListResponse;
import com.cadence.api.common.RecomputeLockRegistry;
import com.cadence.api.common.SseHeartbeat;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
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

	private static final String LOCK_KIND = "best-efforts";
	private static final long HEARTBEAT_INTERVAL_SECONDS = 20;

	private final BestEffortRepository bestEffortRepository;
	private final BestEffortRecomputeService recomputeService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final RecomputeLockRegistry lockRegistry;
	private final Executor taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public BestEffortController(BestEffortRepository bestEffortRepository,
			BestEffortRecomputeService recomputeService, UserService userService,
			AccessGuard accessGuard, RecomputeLockRegistry lockRegistry) {
		this.bestEffortRepository = bestEffortRepository;
		this.recomputeService = recomputeService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.lockRegistry = lockRegistry;
	}

	@GetMapping("/v1/athletes/{id}/best-efforts")
	public BestEffortListResponse listBestEfforts(@PathVariable String id,
			@RequestParam BestEffortKind kind,
			@RequestParam(defaultValue = "all") String period) {
		accessGuard.requireRead(id);
		// 4w/16w match BestEffortWindows.TRIM_PERIOD_DAYS exactly - querying the exact displayed
		// period directly (rather than fetching a wider bucket and narrowing client-side) avoids
		// capPerWindow discarding entries that are top-N within the narrower window but not
		// within the top-N of a wider one it happened to be fetched from.
		LocalDate since = switch (period) {
			case "4w" -> LocalDate.now().minusDays(28);
			case "3m" -> LocalDate.now().minusDays(90);
			case "16w" -> LocalDate.now().minusDays(112);
			case "1y" -> LocalDate.now().minusDays(365);
			default -> null;
		};
		List<BestEffort> efforts = since != null
				? bestEffortRepository.findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindowAscValueDesc(id, kind, since)
				: bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(id, kind);

		int topN = userService.getById(id).getBestEffortTopN();
		List<BestEffort> capped = capPerWindow(efforts, kind == BestEffortKind.RUNNING_PACE, topN);

		List<BestEffortResponse> data = capped.stream()
				.map(e -> new BestEffortResponse(e.getWindow(), e.getValue(), e.getUnit(), e.getDate(), e.getActivity().getId()))
				.toList();
		return new BestEffortListResponse(kind, period, data);
	}

	/**
	 * Trim retains up to topN rows per window in EACH tracked period independently (see
	 * BestEffortComputeService#trimToTop), so a single date-filtered read can still return more
	 * than topN rows for one window - e.g. the top-10-of-112-days set and the top-10-of-365-days
	 * set can differ, and a query spanning both periods sees their union. This re-caps to the
	 * true top N by value (respecting direction) before returning, preserving the
	 * window-asc/value-desc order callers expect.
	 */
	public static List<BestEffort> capPerWindow(List<BestEffort> efforts, boolean lowerIsBetter, int topN) {
		if (topN <= 0) return efforts; // 0 = unlimited, matching trimToTop's own "0 = keep all"
		Map<String, List<BestEffort>> byWindow = efforts.stream()
				.collect(Collectors.groupingBy(BestEffort::getWindow, LinkedHashMap::new, Collectors.toList()));
		Comparator<BestEffort> byRank = lowerIsBetter
				? Comparator.comparingDouble(BestEffort::getValue)
				: Comparator.comparingDouble(BestEffort::getValue).reversed();
		List<BestEffort> capped = new ArrayList<>();
		for (List<BestEffort> windowEfforts : byWindow.values()) {
			capped.addAll(windowEfforts.stream().sorted(byRank).limit(topN).toList());
		}
		capped.sort(Comparator.comparing(BestEffort::getWindow)
				.thenComparing(Comparator.comparingDouble(BestEffort::getValue).reversed()));
		return capped;
	}

	// The 4w/3m periods are deliberately excluded here (unlike listBestEfforts above) - they
	// flag almost everything recent, which is exactly what the Dashboard "Top efforts this
	// week" card is trying to avoid drowning the athlete in.
	private static final List<String> RECENT_TOP_EFFORT_PERIODS = List.of("16w", "1y", "all");

	/**
	 * Backs the Dashboard's "Top efforts this week" card: which of an athlete's activities from
	 * the last {@code days} were good enough to rank top-3 in any of their best-effort
	 * leaderboards (16-week, 1-year, or all-time - see {@link #RECENT_TOP_EFFORT_PERIODS}).
	 *
	 * <p>An "entry" is one activity x one best-effort category (kind + window). For each kind
	 * and each tracked period, this takes the exact same (capped, top-N) row set {@link
	 * #listBestEfforts} would return for that period, ranks each window's rows by value
	 * (respecting direction), and keeps only the ones whose activity falls within the last
	 * {@code days}. The same (activity, kind, window) triple can appear across multiple
	 * periods - its {@code ranks} map accumulates one entry per period it showed up in, {@code
	 * null} for a period it didn't reach the top-N of at all (not "0 rank", a real absence).
	 * Only entries with at least one period's rank &lt;= 3 are returned - sorting/grouping/
	 * headline-period selection all happen client-side (see the design handoff's
	 * TopEffortsCard.tsx).
	 *
	 * <p>Retention caveat: trim keeps {@code bestEffortTopN} rows per window <em>per period</em>
	 * independently (see {@link #capPerWindow}'s own Javadoc), so ranks 1-3 are always
	 * recoverable as long as {@code bestEffortTopN >= 3}. An athlete with it set to 1-2 just
	 * gets ranked out of whatever smaller set actually exists - not an error case, nothing extra
	 * to handle here.
	 */
	@Transactional
	@GetMapping("/v1/athletes/{id}/best-efforts/recent-ranks")
	public RecentTopEffortsListResponse recentTopEffortRanks(@PathVariable String id,
			@RequestParam(defaultValue = "7") int days) {
		accessGuard.requireRead(id);
		User athlete = userService.getById(id);
		LocalDate since = LocalDate.now().minusDays(days);

		record EntryKey(String activityId, BestEffortKind kind, String window) {
		}

		Map<EntryKey, RecentTopEffortAccumulator> entries = new LinkedHashMap<>();
		for (BestEffortKind kind : BestEffortKind.values()) {
			boolean lowerIsBetter = kind == BestEffortKind.RUNNING_PACE;
			for (String period : RECENT_TOP_EFFORT_PERIODS) {
				LocalDate periodSince = recentTopEffortPeriodCutoff(period);
				List<BestEffort> efforts = periodSince != null
						? bestEffortRepository.findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindowAscValueDesc(id, kind, periodSince)
						: bestEffortRepository.findByAthleteIdAndKindOrderByWindowAscValueDesc(id, kind);
				List<BestEffort> capped = capPerWindow(efforts, lowerIsBetter, athlete.getBestEffortTopN());

				Map<String, List<BestEffort>> byWindow = capped.stream()
						.collect(Collectors.groupingBy(BestEffort::getWindow, LinkedHashMap::new, Collectors.toList()));
				for (List<BestEffort> windowEfforts : byWindow.values()) {
					// Rank order is direction-aware and computed fresh here, not inherited from
					// capPerWindow's own return order - that method's final sort is always
					// value-desc for display purposes, which is actually *reverse* rank order
					// for a lower-is-better kind (pace).
					Comparator<BestEffort> byRank = lowerIsBetter
							? Comparator.comparingDouble(BestEffort::getValue)
							: Comparator.comparingDouble(BestEffort::getValue).reversed();
					List<BestEffort> ranked = windowEfforts.stream().sorted(byRank).toList();
					for (int i = 0; i < ranked.size(); i++) {
						BestEffort effort = ranked.get(i);
						if (effort.getDate().isBefore(since)) {
							continue;
						}
						EntryKey key = new EntryKey(effort.getActivity().getId(), effort.getKind(), effort.getWindow());
						RecentTopEffortAccumulator entry =
								entries.computeIfAbsent(key, k -> new RecentTopEffortAccumulator(effort));
						entry.ranks.put(period, i + 1);
					}
				}
			}
		}

		List<RecentTopEffortResponse> data = entries.values().stream()
				.filter(e -> e.ranks.values().stream().anyMatch(r -> r <= 3))
				.map(e -> new RecentTopEffortResponse(e.activityId, e.date, e.sport, e.kind, e.window, e.value, e.unit, e.ranks))
				.toList();
		return new RecentTopEffortsListResponse(since.toString(), data);
	}

	private static LocalDate recentTopEffortPeriodCutoff(String period) {
		return switch (period) {
			case "16w" -> LocalDate.now().minusDays(112);
			case "1y" -> LocalDate.now().minusDays(365);
			default -> null; // "all"
		};
	}

	/** Mutable accumulator for one (activity, kind, window) entry while {@link
	 * #recentTopEffortRanks} walks every kind/period combination - a plain record can't be
	 * built incrementally, since the same entry's {@code ranks} map gets filled in across
	 * multiple, separate period passes. */
	private static final class RecentTopEffortAccumulator {
		final String activityId;
		final LocalDate date;
		final Sport sport;
		final BestEffortKind kind;
		final String window;
		final double value;
		final String unit;
		final Map<String, Integer> ranks = new LinkedHashMap<>();

		RecentTopEffortAccumulator(BestEffort effort) {
			this.activityId = effort.getActivity().getId();
			this.date = effort.getDate();
			this.sport = effort.getActivity().getSport();
			this.kind = effort.getKind();
			this.window = effort.getWindow();
			this.value = effort.getValue();
			this.unit = effort.getUnit();
			for (String period : RECENT_TOP_EFFORT_PERIODS) {
				this.ranks.put(period, null);
			}
		}
	}

	@DeleteMapping("/v1/athletes/{id}/best-efforts/by-activity/{activityId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	public void excludeActivity(@PathVariable String id, @PathVariable String activityId,
			@RequestParam BestEffortKind kind) {
		accessGuard.requireRead(id);
		bestEffortRepository.deleteByAthleteIdAndKindAndActivityId(id, kind, activityId);
	}

	/**
	 * A full account can legitimately take hours (thousands of activities, each its own DB
	 * round-trip) - seen for real in production. Two things that bit us there, both fixed here:
	 * <ul>
	 * <li>{@code SseEmitter}'s timeout is wall-clock, not an idle timer - a value long enough
	 * for a quick account still eventually kills a slow one. {@code 0L} means no timeout at
	 * all (see {@code jakarta.servlet.AsyncContext#setTimeout}'s Javadoc: "A timeout value of
	 * zero or less indicates no timeout" - Spring's SseEmitter passes its constructor value
	 * straight through to this).</li>
	 * <li>The background task isn't tied to this connection's lifecycle at all - closing the
	 * browser tab doesn't stop it. So a user who thinks a stalled/disconnected run died and
	 * clicks the button again can end up with two runs racing on the same athlete's rows
	 * (recomputeAll deletes-then-rewrites by id) - one loses with a Hibernate
	 * StaleStateException. {@link RecomputeLockRegistry} rejects the second one instead.</li>
	 * </ul>
	 */
	@PostMapping(value = "/v1/athletes/{id}/best-efforts/recompute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter recompute(@PathVariable String id,
			@RequestParam(required = false) BestEffortKind kind) {
		accessGuard.requireWrite(id);
		if (!lockRegistry.tryAcquire(LOCK_KIND, id)) {
			throw new ConflictException("A best-efforts recompute is already running for this athlete.");
		}
		User athlete = userService.getById(id);
		SseEmitter emitter = new SseEmitter(0L);
		SseHeartbeat heartbeat = new SseHeartbeat(
				() -> emitter.send(SseEmitter.event().name("heartbeat").data("{}")), HEARTBEAT_INTERVAL_SECONDS);

		taskExecutor.execute(() -> {
			try {
				int processed = (kind != null)
						? recomputeService.recompute(athlete, kind, (current, total) -> sendProgress(emitter, current, total))
						: recomputeService.recomputeAll(athlete, (current, total) -> sendProgress(emitter, current, total));
				emitter.send(SseEmitter.event()
						.name("done")
						.data("{\"processed\":" + processed + "}"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			} finally {
				heartbeat.close();
				lockRegistry.release(LOCK_KIND, id);
			}
		});

		return emitter;
	}

	private void sendProgress(SseEmitter emitter, int current, int total) {
		try {
			emitter.send(SseEmitter.event()
					.data("{\"current\":" + current + ",\"total\":" + total + "}"));
		} catch (Exception e) {
			// Client disconnected (IOException), or the emitter already completed some other
			// way (e.g. IllegalStateException) - either way the emitter itself will settle on
			// its own; nothing here should interrupt the recompute loop still in progress.
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
