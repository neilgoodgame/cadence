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
		if (activityTagRepository.existsByTagId(tag.getId())) {
			throw new ConflictException("This tag is still linked to activities.");
		}
		tagRepository.delete(tag);
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
