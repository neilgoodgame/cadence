package com.cadence.api.workouts;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.Lap;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.workouts.dto.InferredWorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Infers a {@link WorkoutStepDto} tree from an already-recorded {@link Activity}'s laps. Zwift
 * (and most structured-workout-capable devices) auto-laps at every segment change in a structured
 * workout, so lap boundaries are usually a good proxy for step boundaries. Optionally collapses
 * repeated contiguous patterns (e.g. 5x[work, rest]) into {@code repeat} groups.
 *
 * <p>Pure/read-only beyond the athlete's FTP/LTHR lookup - nothing here persists anything.
 * Port of {@code backend/workouts/inference.py}; keep the tuning constants and algorithm in sync.
 */
@Service
public class WorkoutInferenceService {

	// Tuning knobs - the things to retune once run against real activity history.
	private static final double DURATION_TOLERANCE_FRACTION = 0.15;
	private static final double DURATION_TOLERANCE_MIN_SECONDS = 10;
	private static final double PCT_TOLERANCE = 5;
	private static final int MIN_REPEAT_COUNT = 2;
	private static final int MAX_COMPRESSION_PASSES = 4;
	private static final double WORK_THRESHOLD_PCT = 85;

	private final ZoneService zoneService;

	public WorkoutInferenceService(ZoneService zoneService) {
		this.zoneService = zoneService;
	}

	/**
	 * An intermediate leaf/group representation carrying enough info for both step-tree output
	 * and fuzzy repeat-pattern matching, before {@code kind} classification (which needs the
	 * full sequence, not just one lap) is applied.
	 */
	sealed interface Node {
		record LeafCandidate(int duration, TargetType targetType, Double pct) implements Node {
		}

		record Group(int repeat, List<Node> children) implements Node {
		}
	}

	public InferredWorkoutResponse infer(Activity activity, List<Lap> laps, boolean autoDetectRepeats) {
		List<Node.LeafCandidate> leaves = leavesFromActivity(activity, laps);
		List<Node> nodes = autoDetectRepeats ? detectRepeats(leaves) : new ArrayList<>(leaves);
		List<WorkoutStepDto> steps = new ArrayList<>(nodes.stream().map(this::nodeToStep).toList());

		// warmup/cool are a top-level-only override: the first/last *leaf* step (never a repeat
		// group, which can't sensibly be a warmup/cooldown) gets relabeled if it looks like an
		// easy lead-in/cooldown rather than a work interval.
		if (!steps.isEmpty() && steps.get(0).kind() == StepKind.REC) {
			steps.set(0, withKind(steps.get(0), StepKind.WARMUP));
		}
		if (!steps.isEmpty() && steps.get(steps.size() - 1).kind() == StepKind.REC) {
			int last = steps.size() - 1;
			steps.set(last, withKind(steps.get(last), StepKind.COOL));
		}

		return new InferredWorkoutResponse(activity.getName(), activity.getSport(), steps);
	}

	private List<Node.LeafCandidate> leavesFromActivity(Activity activity, List<Lap> laps) {
		ZoneType zoneType = activity.getSport() == Sport.BIKE ? ZoneType.BIKE_POWER : ZoneType.RUN_POWER;
		Double ftp = zoneService.referenceFor(activity.getAthlete(), zoneType);
		Double lthr = zoneService.referenceFor(activity.getAthlete(), ZoneType.HEART_RATE);
		return laps.stream().map(lap -> leafFromLap(lap, ftp, lthr)).toList();
	}

	private Node.LeafCandidate leafFromLap(Lap lap, Double ftp, Double lthr) {
		if (lap.getAvgPower() != null && ftp != null && ftp != 0) {
			return new Node.LeafCandidate(lap.getDuration(), TargetType.POWER, pctOf(lap.getAvgPower(), ftp));
		}
		if (lap.getAvgHr() != null && lthr != null && lthr != 0) {
			return new Node.LeafCandidate(lap.getDuration(), TargetType.HR, pctOf(lap.getAvgHr(), lthr));
		}
		return new Node.LeafCandidate(lap.getDuration(), TargetType.OPEN, null);
	}

	private static double pctOf(int value, double reference) {
		return Math.round(100 * value / reference);
	}

	private static double durationTolerance(int a, int b) {
		return Math.max(DURATION_TOLERANCE_MIN_SECONDS, DURATION_TOLERANCE_FRACTION * Math.max(a, b));
	}

	private static boolean leavesEquivalent(Node.LeafCandidate a, Node.LeafCandidate b) {
		if (a.targetType() != b.targetType()) {
			return false;
		}
		if (Math.abs(a.duration() - b.duration()) > durationTolerance(a.duration(), b.duration())) {
			return false;
		}
		if (a.pct() == null || b.pct() == null) {
			return Objects.equals(a.pct(), b.pct());
		}
		return Math.abs(a.pct() - b.pct()) <= PCT_TOLERANCE;
	}

	private static boolean nodesEquivalent(Node a, Node b) {
		if (a instanceof Node.Group ga && b instanceof Node.Group gb) {
			if (ga.repeat() != gb.repeat() || ga.children().size() != gb.children().size()) {
				return false;
			}
			for (int i = 0; i < ga.children().size(); i++) {
				if (!nodesEquivalent(ga.children().get(i), gb.children().get(i))) {
					return false;
				}
			}
			return true;
		}
		if (a instanceof Node.LeafCandidate la && b instanceof Node.LeafCandidate lb) {
			return leavesEquivalent(la, lb);
		}
		return false;
	}

	private static boolean blocksEquivalent(List<Node> a, List<Node> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (!nodesEquivalent(a.get(i), b.get(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * One left-to-right greedy scan: at each position, find the (pattern length, repeat count)
	 * that covers the most nodes with the pattern repeating at least {@link #MIN_REPEAT_COUNT}
	 * times, and collapse it into a {@link Node.Group}. Ties on coverage favor the shorter
	 * pattern (a smaller, confidently-detected block over a longer, coincidental one).
	 */
	// Package-private (rather than private) so the pure pattern-detection algorithm can be
	// unit-tested directly, without going through infer()'s Activity/Lap/ZoneService plumbing.
	static List<Node> compressPass(List<Node> nodes) {
		int n = nodes.size();
		List<Node> result = new ArrayList<>();
		int i = 0;
		while (i < n) {
			int bestP = -1;
			int bestK = -1;
			int maxPatternLen = (n - i) / MIN_REPEAT_COUNT;
			for (int p = 1; p <= maxPatternLen; p++) {
				List<Node> block = nodes.subList(i, i + p);
				int k = 1;
				int j = i + p;
				while (j + p <= n && blocksEquivalent(block, nodes.subList(j, j + p))) {
					k++;
					j += p;
				}
				if (k >= MIN_REPEAT_COUNT) {
					int covered = p * k;
					int bestCovered = bestP < 0 ? -1 : bestP * bestK;
					if (bestP < 0 || covered > bestCovered || (covered == bestCovered && p < bestP)) {
						bestP = p;
						bestK = k;
					}
				}
			}
			if (bestP > 0) {
				result.add(new Node.Group(bestK, new ArrayList<>(nodes.subList(i, i + bestP))));
				i += bestP * bestK;
			}
			else {
				result.add(nodes.get(i));
				i += 1;
			}
		}
		return result;
	}

	private static List<Node> detectRepeats(List<Node.LeafCandidate> leaves) {
		List<Node> nodes = new ArrayList<>(leaves);
		for (int pass = 0; pass < MAX_COMPRESSION_PASSES; pass++) {
			List<Node> compressed = compressPass(nodes);
			if (compressed.equals(nodes) || compressed.size() == nodes.size()) {
				return compressed;
			}
			nodes = compressed;
		}
		return nodes;
	}

	/**
	 * block/rec only - warmup/cool are a first/last-*top-level*-entry override applied
	 * afterwards in {@link #infer}, since a leaf's position in the original lap sequence doesn't
	 * survive repeat-group collapsing (a group's children is just the repeated pattern, not
	 * every original lap it was built from) and a repeat group's own children should never be
	 * classified warmup/cool anyway.
	 */
	private static StepKind classifyKind(Node.LeafCandidate leaf) {
		double pct = leaf.pct() != null ? leaf.pct() : 100;
		return pct >= WORK_THRESHOLD_PCT ? StepKind.BLOCK : StepKind.REC;
	}

	private static WorkoutStepDto leafToStep(Node.LeafCandidate leaf) {
		// Inference always reconstructs steps in %-space (pct-of-threshold), never watts.
		return new WorkoutStepDto(classifyKind(leaf), StepEndType.TIME, leaf.duration(), null, leaf.targetType(),
				leaf.pct(), leaf.pct(), PowerUnit.PCT_FTP, Target2Type.NONE, null, null, 1, "", null);
	}

	private WorkoutStepDto groupToStep(Node.Group group) {
		List<WorkoutStepDto> children = group.children().stream().map(this::nodeToStep).toList();
		return new WorkoutStepDto(StepKind.REPEAT, null, null, null, null, null, null, PowerUnit.PCT_FTP,
				Target2Type.NONE, null, null, group.repeat(), "", children);
	}

	private WorkoutStepDto nodeToStep(Node node) {
		return switch (node) {
			case Node.Group g -> groupToStep(g);
			case Node.LeafCandidate l -> leafToStep(l);
		};
	}

	private static WorkoutStepDto withKind(WorkoutStepDto dto, StepKind kind) {
		return new WorkoutStepDto(kind, dto.endType(), dto.duration(), dto.distance(), dto.targetType(),
				dto.targetLow(), dto.targetHigh(), dto.powerUnit(), dto.target2Type(), dto.target2Low(),
				dto.target2High(), dto.repeat(), dto.note(), dto.children());
	}
}
