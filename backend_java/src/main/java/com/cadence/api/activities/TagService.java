package com.cadence.api.activities;

import com.cadence.api.activities.dto.TagResponse;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {

	private final TagRepository tagRepository;
	private final ActivityTagRepository activityTagRepository;

	public TagService(TagRepository tagRepository, ActivityTagRepository activityTagRepository) {
		this.tagRepository = tagRepository;
		this.activityTagRepository = activityTagRepository;
	}

	public List<Tag> listTags(String athleteId) {
		return tagRepository.findByAthleteIdOrderByName(athleteId);
	}

	/** Same tags as {@link #listTags}, annotated with an athlete-scoped usage count and
	 * ordered most-used first (ties broken alphabetically, same as the frontend/design
	 * spec's tag filter bar). */
	public List<TagResponse> listTagsWithCounts(String athleteId) {
		Map<String, Long> counts = activityTagRepository.countByAthleteId(athleteId).stream()
				.collect(Collectors.toMap(ActivityTagRepository.TagUsageCount::getTagId,
						ActivityTagRepository.TagUsageCount::getUsageCount));
		return tagRepository.findByAthleteIdOrderByName(athleteId).stream()
				.map(t -> new TagResponse(t.getId(), t.getName(), t.getOrigin(), t.getColor(),
						counts.getOrDefault(t.getId(), 0L)))
				.sorted(Comparator.comparingLong(TagResponse::count).reversed())
				.toList();
	}

	/** Creates a standalone tag with no activities yet - for the Preferences tag manager.
	 * {@link #attachTag} already creates one implicitly when attaching by name; this is for the
	 * case where nothing to attach to exists yet. Rejects a name that collides
	 * case-insensitively with an existing tag rather than silently reusing it, since a person
	 * explicitly creating a new tag should be told it already exists. */
	@Transactional
	public Tag createTag(String athleteId, User athlete, String name) {
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException("name cannot be empty.", "name");
		}
		if (!tagRepository.findAllByAthleteIdAndNameIgnoreCase(athleteId, trimmed).isEmpty()) {
			throw new ConflictException("A tag named \"" + trimmed + "\" already exists.");
		}
		Tag tag = new Tag();
		tag.setAthlete(athlete);
		tag.setName(trimmed);
		tag.setOrigin(TagOrigin.MANUAL);
		return tagRepository.save(tag);
	}

	@Transactional
	public Tag attachTag(Activity activity, User athlete, String tagId, String name) {
		Tag tag;
		if (tagId != null && !tagId.isBlank()) {
			tag = tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("No such tag."));
		}
		else if (name != null && !name.isBlank()) {
			tag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), name).orElseGet(() -> {
				Tag created = new Tag();
				created.setAthlete(athlete);
				created.setName(name);
				created.setOrigin(TagOrigin.MANUAL);
				return tagRepository.save(created);
			});
		}
		else {
			throw new ValidationException("Provide either tag_id or name.", "tag_id");
		}

		if (!activityTagRepository.existsByActivityIdAndTagId(activity.getId(), tag.getId())) {
			ActivityTag link = new ActivityTag();
			link.setActivity(activity);
			link.setTag(tag);
			activityTagRepository.save(link);
		}
		return tag;
	}

	@Transactional
	public void deleteTag(String athleteId, String tagId) {
		Tag tag = tagRepository.findByIdAndAthleteId(tagId, athleteId)
				.orElseThrow(() -> new NotFoundException("No such tag."));
		// Cascades: activity_tag.tag_id is ON DELETE CASCADE, so every link to this tag goes
		// with it - deleting a tag always fully removes it, not just when unused.
		tagRepository.delete(tag);
	}

	/** Exact match first: if the athlete has both "race" and "Race" (possible via the older
	 * exact-match attach-by-name path), an ignore-case-only lookup would be ambiguous about
	 * which one "race" refers to. Falls back to case-insensitive so a caller unsure of the exact
	 * casing still finds the tag in the common case where no such duplicate exists. */
	public Tag findByName(String athleteId, String name) {
		return tagRepository.findByAthleteIdAndName(athleteId, name)
				.or(() -> tagRepository.findByAthleteIdAndNameIgnoreCase(athleteId, name))
				.orElseThrow(() -> new NotFoundException("No tag named \"" + name + "\"."));
	}

	/** Renames `tag` to `newName`. If the athlete already has a different tag with that name
	 * (case-insensitive), merges into it instead: every activity linked to `tag` ends up linked
	 * to the existing tag (an activity that already carries both keeps just the one link), and
	 * `tag` is deleted - its now-orphaned links go with it via activity_tag.tag_id's ON DELETE
	 * CASCADE. Returns whichever tag now holds `newName`: `tag` itself (renamed in place) or the
	 * pre-existing one it was merged into. */
	@Transactional
	public Tag renameTag(String athleteId, String tagId, String newName) {
		Tag tag = tagRepository.findByIdAndAthleteId(tagId, athleteId)
				.orElseThrow(() -> new NotFoundException("No such tag."));
		String trimmed = newName == null ? "" : newName.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException("name cannot be empty.", "name");
		}
		List<Tag> candidates = tagRepository.findAllByAthleteIdAndNameIgnoreCase(athleteId, trimmed).stream()
				.filter(t -> !t.getId().equals(tag.getId()))
				.toList();
		// Prefer an exact-name match among candidates (the tag that already is named exactly
		// `trimmed`) over an arbitrary case-insensitive one, for the rare case where the athlete
		// already has more than one case-variant of this name.
		Optional<Tag> existing = candidates.stream().filter(t -> t.getName().equals(trimmed)).findFirst()
				.or(() -> candidates.stream().findFirst());
		if (existing.isEmpty()) {
			tag.setName(trimmed);
			return tagRepository.save(tag);
		}
		Tag target = existing.get();
		// Drop the old-tag link for any activity that already carries the target tag too (would
		// otherwise violate activity_tag's unique(activity_id, tag_id) once repointed), then
		// repoint everything else.
		activityTagRepository.deleteLinksAlreadyOnTarget(tag, target);
		activityTagRepository.repointLinks(tag, target);
		tagRepository.delete(tag);
		return target;
	}

	/** Auto-applied tags (e.g. "Auto-matched" from workout matching) can't be detached by users. */
	@Transactional
	public void detachTag(String activityId, String tagId) {
		ActivityTag link = activityTagRepository.findByActivityIdAndTagId(activityId, tagId)
				.orElseThrow(() -> new NotFoundException("This tag is not attached to this activity."));
		if (link.getTag().getOrigin() == TagOrigin.AUTO) {
			throw new ForbiddenException("Auto-applied tags can't be removed.");
		}
		activityTagRepository.delete(link);
	}
}
