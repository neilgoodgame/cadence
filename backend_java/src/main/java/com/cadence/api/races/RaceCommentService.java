package com.cadence.api.races;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.races.dto.RaceCommentResponse;
import com.cadence.api.users.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Race-plan comments - unlike {@code ActivityCommentService}, gated by the same self-or-coach-
 * with-grant permission as the rest of the races resource (RaceController's AccessGuard calls),
 * not by read access alone - a plain viewer share can read a race but not comment on it. That
 * invariant (only the athlete or a coach-with-write-access ever reaches create()) is why
 * snapshotRole below can shortcut straight to ATHLETE/COACH without a UserRelationship lookup.
 */
@Service
public class RaceCommentService {

	private final RaceCommentRepository raceCommentRepository;

	public RaceCommentService(RaceCommentRepository raceCommentRepository) {
		this.raceCommentRepository = raceCommentRepository;
	}

	@Transactional(readOnly = true)
	public List<RaceComment> list(String raceId) {
		return raceCommentRepository.findByRaceIdWithAuthorOrderByCreatedAt(raceId);
	}

	@Transactional
	public RaceComment create(Race race, User author, String text, Integer week, String parentId) {
		RaceComment comment = new RaceComment();
		comment.setRace(race);
		comment.setAuthor(author);
		comment.setRole(snapshotRole(race, author));
		comment.setText(text);
		comment.setWeek(week);
		if (parentId != null) {
			RaceComment parent = raceCommentRepository.findById(parentId)
					.orElseThrow(() -> new NotFoundException("No such comment."));
			if (!parent.getRace().getId().equals(race.getId())) {
				throw new ValidationException("That comment is not on this race.", "parentId");
			}
			if (parent.getParent() != null) {
				throw new ValidationException("Cannot reply to a reply - only top-level comments can be replied to.", "parentId");
			}
			comment.setParent(parent);
		}
		return raceCommentRepository.save(comment);
	}

	@Transactional
	public void delete(String raceId, String commentId, String requesterId) {
		RaceComment comment = raceCommentRepository.findById(commentId)
				.filter(c -> c.getRace().getId().equals(raceId))
				.orElseThrow(() -> new NotFoundException("No such comment."));
		if (!comment.getAuthor().getId().equals(requesterId)) {
			throw new ForbiddenException("You can only delete your own comments.");
		}
		raceCommentRepository.delete(comment);
	}

	/** The author's role at post time - stored, not recomputed later (see RaceComment's Javadoc). */
	private RaceCommentRole snapshotRole(Race race, User author) {
		return author.getId().equals(race.getAthlete().getId()) ? RaceCommentRole.ATHLETE : RaceCommentRole.COACH;
	}

	public RaceCommentResponse toResponse(RaceComment comment) {
		String parentId = comment.getParent() != null ? comment.getParent().getId() : null;
		return new RaceCommentResponse(comment.getId(), comment.getRace().getId(), comment.getAuthor().getId(),
				comment.getAuthor().getName(), comment.getRole().wireValue(), comment.getText(), comment.getWeek(),
				parentId, comment.getCreatedAt());
	}
}
