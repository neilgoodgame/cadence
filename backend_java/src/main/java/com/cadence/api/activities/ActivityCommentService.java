package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivityCommentResponse;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
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

	@Transactional
	public ActivityCommentResponse create(Activity activity, User author, String text) {
		ActivityComment comment = new ActivityComment();
		comment.setActivity(activity);
		comment.setAuthor(author);
		comment.setText(text);
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
		return new ActivityCommentResponse(comment.getId(), comment.getActivity().getId(), comment.getAuthor().getId(),
				comment.getAuthor().getName(), authorRole(comment), comment.getText(), comment.getCreated());
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
