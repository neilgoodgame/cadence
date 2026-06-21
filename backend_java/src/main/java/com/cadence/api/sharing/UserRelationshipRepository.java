package com.cadence.api.sharing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRelationshipRepository extends JpaRepository<UserRelationship, String> {

	Optional<UserRelationship> findByOwnerIdAndGranteeId(String ownerId, String granteeId);

	Optional<UserRelationship> findByOwnerIdAndGranteeIdAndStatus(String ownerId, String granteeId, ShareStatus status);

	List<UserRelationship> findByOwnerIdAndStatus(String ownerId, ShareStatus status);

	List<UserRelationship> findByGranteeIdAndStatus(String granteeId, ShareStatus status);

	List<UserRelationship> findByGranteeId(String granteeId);

	List<UserRelationship> findByOwnerIdOrderByCreatedDesc(String ownerId);

	// Callers map these straight to a response DTO touching both ends of the relationship
	// (owner and grantee names/handles) after the loading transaction has closed, so both
	// associations need to come back already initialized.
	@Query("select r from UserRelationship r join fetch r.owner join fetch r.grantee where r.owner.id = :ownerId order by r.created desc")
	List<UserRelationship> findByOwnerIdWithUsersOrderByCreatedDesc(@Param("ownerId") String ownerId);

	@Query("select r from UserRelationship r join fetch r.owner join fetch r.grantee where r.id = :id")
	Optional<UserRelationship> findByIdWithUsers(@Param("id") String id);

	@Query("select r from UserRelationship r join fetch r.owner join fetch r.grantee "
			+ "where r.grantee.id = :granteeId and r.status = com.cadence.api.sharing.ShareStatus.ACTIVE")
	List<UserRelationship> findByGranteeIdAndStatusActiveWithUsers(@Param("granteeId") String granteeId);
}
