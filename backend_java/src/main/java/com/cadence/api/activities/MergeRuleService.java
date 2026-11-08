package com.cadence.api.activities;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #501 (phase 4): reads/writes an athlete's saved {@link MergeRule}s and decides, for a
 * detected overlap pair, which side (if either) plays target vs source under the applicable
 * rule. {@link ActivityOverlapDetectionService} is the only consumer of {@link #resolve}/{@link
 * #assign} - everything else here is the {@code get_merge_rules}/{@code set_merge_rule} MCP
 * tools' backing logic. Mirrors backend's activities/merge_rule_service.py - keep in sync.
 */
@Service
public class MergeRuleService {

	/** No saved rule for a sport not listed here means automatic merging simply doesn't apply -
	 * there's no one obviously-right default to fall back to (see issue #501's own "Default
	 * selection by sport" - bike -> power, every other sport -> nothing pre-selected). */
	private static final Map<Sport, ResolvedRule> BUILT_IN_DEFAULTS = Map.of(Sport.BIKE,
			new ResolvedRule("device:zwift", "device:garmin", List.of("power"), NullPolicy.KEEP_ORIGINAL));

	public record ResolvedRule(String targetPattern, String sourcePattern, List<String> fields, NullPolicy nullPolicy) {
	}

	public record TargetSource(Activity target, Activity source) {
	}

	private final MergeRuleRepository repository;
	private final UserRepository userRepository;

	public MergeRuleService(MergeRuleRepository repository, UserRepository userRepository) {
		this.repository = repository;
		this.userRepository = userRepository;
	}

	/** The athlete's saved rule for {@code sport}, or its built-in default, or null if neither
	 * exists - meaning automatic merging doesn't apply for this sport/athlete. */
	public ResolvedRule resolve(String athleteId, Sport sport) {
		return repository.findByAthleteIdAndSport(athleteId, sport)
				.map(r -> new ResolvedRule(r.getTargetPattern(), r.getSourcePattern(), r.getFields(), r.getNullPolicy()))
				.orElseGet(() -> BUILT_IN_DEFAULTS.get(sport));
	}

	/** Which of {@code a}/{@code b} plays target vs source under {@code rule} - order-independent,
	 * same spirit as {@link ActivityOverlapDetectionService#choosePrimary}. Null if neither
	 * orientation matches (e.g. both activities are Zwift, or neither matches the rule's
	 * patterns at all) - the caller falls back to flagging rather than guessing. */
	public TargetSource assign(Activity a, Activity b, ResolvedRule rule) {
		if (matches(a, rule.targetPattern()) && matches(b, rule.sourcePattern())) {
			return new TargetSource(a, b);
		}
		if (matches(b, rule.targetPattern()) && matches(a, rule.sourcePattern())) {
			return new TargetSource(b, a);
		}
		return null;
	}

	/** {@code pattern} is {@code "device:<prefix>"} - a case-insensitive prefix match against
	 * {@link Activity#getDevice()}. Only {@code device:} patterns exist today. */
	static boolean matches(Activity activity, String pattern) {
		if (pattern == null) {
			return false;
		}
		int colon = pattern.indexOf(':');
		if (colon < 0 || !"device".equals(pattern.substring(0, colon))) {
			return false;
		}
		String prefix = pattern.substring(colon + 1).toLowerCase(Locale.ROOT);
		String device = activity.getDevice();
		return device != null && device.toLowerCase(Locale.ROOT).startsWith(prefix);
	}

	public List<MergeRule> list(String athleteId) {
		return repository.findByAthleteIdOrderBySportAsc(athleteId);
	}

	@Transactional
	public MergeRule setRule(String athleteId, Sport sport, String targetPattern, String sourcePattern,
			List<String> fields, NullPolicy nullPolicy) {
		validatePattern(targetPattern, "target_pattern");
		validatePattern(sourcePattern, "source_pattern");
		if (fields == null || fields.isEmpty()) {
			throw new ValidationException("At least one field must be selected.", "fields");
		}
		List<String> unknown = fields.stream().filter(f -> !ActivityMergeApplyService.isKnownField(f)).toList();
		if (!unknown.isEmpty()) {
			throw new ValidationException("Unknown field(s): " + String.join(", ", unknown), "fields");
		}
		MergeRule rule = repository.findByAthleteIdAndSport(athleteId, sport).orElseGet(() -> {
			MergeRule created = new MergeRule();
			created.setAthlete(userRepository.getReferenceById(athleteId));
			created.setSport(sport);
			return created;
		});
		rule.setTargetPattern(targetPattern);
		rule.setSourcePattern(sourcePattern);
		rule.setFields(fields);
		rule.setNullPolicy(nullPolicy);
		return repository.save(rule);
	}

	private void validatePattern(String pattern, String param) {
		if (pattern == null || !pattern.startsWith("device:") || pattern.length() <= "device:".length()) {
			throw new ValidationException(param + " must be 'device:<prefix>', e.g. 'device:zwift'.", param);
		}
	}
}
