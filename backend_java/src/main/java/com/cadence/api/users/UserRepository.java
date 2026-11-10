package com.cadence.api.users;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<User, String> {

	Optional<User> findByEmailIgnoreCase(String email);

	Optional<User> findByHandleIgnoreCase(String handle);

	boolean existsByEmailIgnoreCase(String email);

	List<User> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email);

	// ThresholdDecayRecomputeService's row lock for the duration of a suffix resimulation (design
	// doc section 4.3) - guards against two triggers (e.g. a future daily job and an ingest)
	// re-simulating the same athlete/field at once. Must be called inside a transaction; the lock
	// is held until that transaction commits or rolls back.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<User> findWithLockById(String id);
}
