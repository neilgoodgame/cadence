package com.cadence.api.users;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

	Optional<User> findByEmailIgnoreCase(String email);

	Optional<User> findByHandleIgnoreCase(String handle);

	boolean existsByEmailIgnoreCase(String email);
}
