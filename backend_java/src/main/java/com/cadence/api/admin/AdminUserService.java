package com.cadence.api.admin;

import com.cadence.api.admin.dto.AdminUserResponse;
import com.cadence.api.admin.dto.AdminUserUpdateRequest;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {

	private final UserRepository userRepository;

	public AdminUserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	public List<AdminUserResponse> list(String q) {
		List<User> users = (q == null || q.isBlank())
				? userRepository.findAll()
				: userRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(q, q);
		return users.stream()
				.sorted(Comparator.comparing(User::getDateJoined).reversed())
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public AdminUserResponse update(String id, AdminUserUpdateRequest request) {
		User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("No such user."));
		if (request.isCoach() != null) {
			user.setCoach(request.isCoach());
		}
		if (request.isAdmin() != null) {
			user.setAdmin(request.isAdmin());
		}
		return toResponse(userRepository.save(user));
	}

	private AdminUserResponse toResponse(User user) {
		return new AdminUserResponse(
				user.getId(), user.getName(), user.getEmail(), user.getDateJoined(), user.isCoach(), user.isAdmin());
	}
}
