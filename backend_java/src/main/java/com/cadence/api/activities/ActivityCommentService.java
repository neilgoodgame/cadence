package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivityCommentResponse;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationshipRepository;
import com.cadence.api.users.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments are a lightweight social feature gated by read access, not write access -
 * anyone who can see an athlete's activity (the athlete, a coach, or a viewer share) can
 * comment on it. See ActivityComment's own docstring for why author_role is derived here
 * at read time rather than stored on the row.
 */
@Service
public class ActivityCommentService {

	private final ActivityCommentRepository activityCommentRepository;
	private final UserRelationshipRepository userRelationshipRepository;

	public ActivityCommentService(ActivityCommentRepository activityCommentRepository,
			UserRelationshipRepository userRelationshipRepository) {
		this.activityCommentRepository = activityCommentRepository;
		this.userRelationshipRepository = userRelationshipRepository;
	}

	@Transactional(readOnly = true)
	public List<ActivityCommentResponse> list(String activityId) {
		return activityCommentRepository.findByActivityIdWithAuthorOrderByCreated(activityId).stream()
				.map(this::toResponse)
				.toList();
	}

	/**
	 * {@code parentId} null creates a top-level comment. Non-null replies to that comment -
	 * single-level threading only, so the parent must itself be a top-level comment (its own
	 * {@code parent} must be null) and belong to the same activity; replying to a reply is
	 * rejected rather than silently re-parented to the root, so a caller passing a stale/wrong
	 * id finds out immediately instead of the thread quietly reshaping itself.
	 */
	@Transactional
	public ActivityCommentResponse create(Activity activity, User author, String text, String parentId) {
		ActivityComment comment = new ActivityComment();
		comment.setActivity(activity);
		comment.setAuthor(author);
		comment.setText(text);
		if (parentId != null) {
			ActivityComment parent = activityCommentRepository.findById(parentId)
					.orElseThrow(() -> new NotFoundException("No such comment."));
			if (!parent.getActivity().getId().equals(activity.getId())) {
				throw new ValidationException("That comment is not on this activity.", "parentId");
			}
			if (parent.getParent() != null) {
				throw new ValidationException("Cannot reply to a reply - only top-level comments can be replied to.", "parentId");
			}
			comment.setParent(parent);
		}
		activityCommentRepository.save(comment);
		return toResponse(comment);
	}

	@Transactional
	public void delete(String activityId, String commentId, String requesterId) {
		ActivityComment comment = activityCommentRepository.findById(commentId)
				.filter(c -> c.getActivity().getId().equals(activityId))
				.orElseThrow(() -> new NotFoundException("No such comment."));
		if (!comment.getAuthor().getId().equals(requesterId)) {
			throw new ForbiddenException("You can only delete your own comments.");
		}
		activityCommentRepository.delete(comment);
	}

	private ActivityCommentResponse toResponse(ActivityComment comment) {
		// .getId() only - safe on an uninitialized lazy proxy regardless of whether the loading
		// query join-fetched `parent` (list() doesn't; create() has it as a real entity either
		// way) - see SchedulingMapper's Javadoc for the general pattern this follows.
		String parentId = comment.getParent() != null ? comment.getParent().getId() : null;
		return new ActivityCommentResponse(comment.getId(), comment.getActivity().getId(), comment.getAuthor().getId(),
				comment.getAuthor().getName(), authorRole(comment), parentId, comment.getText(), comment.getCreated());
	}

	private String authorRole(ActivityComment comment) {
		String authorId = comment.getAuthor().getId();
		String athleteId = comment.getActivity().getAthlete().getId();
		if (authorId.equals(athleteId)) {
			return "athlete";
		}
		return userRelationshipRepository.findByOwnerIdAndGranteeIdAndStatus(athleteId, authorId, ShareStatus.ACTIVE)
				.map(relationship -> relationship.getRole().wireValue())
				.orElse("viewer");
	}
}
