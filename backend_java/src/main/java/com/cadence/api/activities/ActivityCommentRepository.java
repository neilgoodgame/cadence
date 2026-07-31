package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityCommentRepository extends JpaRepository<ActivityComment, String> {

	// Callers map straight to a response DTO touching the author's name, so it needs to
	// come back already initialized - same convention as
	// UserRelationshipRepository.findByOwnerIdWithUsersOrderByCreatedDesc.
	@Query("select c from ActivityComment c join fetch c.author where c.activity.id = :activityId order by c.created")
	List<ActivityComment> findByActivityIdWithAuthorOrderByCreated(@Param("activityId") String activityId);
}
