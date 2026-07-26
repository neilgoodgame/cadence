package com.cadence.api.workouts;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import com.cadence.api.workouts.dto.WorkoutFolderResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkoutFolderService {

	private final WorkoutFolderRepository workoutFolderRepository;
	private final WorkoutRepository workoutRepository;

	public WorkoutFolderService(WorkoutFolderRepository workoutFolderRepository, WorkoutRepository workoutRepository) {
		this.workoutFolderRepository = workoutFolderRepository;
		this.workoutRepository = workoutRepository;
	}

	public List<WorkoutFolderResponse> listFolders(String athleteId) {
		return workoutFolderRepository.findByCreatedByIdOrderByNameAsc(athleteId).stream()
				.map(f -> toResponse(f, workoutRepository.countByFolderId(f.getId())))
				.toList();
	}

	public WorkoutFolder getFolder(String id) {
		return workoutFolderRepository.findById(id).orElseThrow(() -> new NotFoundException("No such folder."));
	}

	@Transactional
	public WorkoutFolderResponse createFolder(User creator, String name) {
		String trimmed = requireName(name);
		if (workoutFolderRepository.existsByCreatedByIdAndName(creator.getId(), trimmed)) {
			throw new ValidationException("A folder with this name already exists.", "name");
		}
		WorkoutFolder folder = new WorkoutFolder();
		folder.setCreatedBy(creator);
		folder.setName(trimmed);
		workoutFolderRepository.save(folder);
		return toResponse(folder, 0);
	}

	@Transactional
	public WorkoutFolderResponse renameFolder(WorkoutFolder folder, String name) {
		String trimmed = requireName(name);
		if (!trimmed.equals(folder.getName())
				&& workoutFolderRepository.existsByCreatedByIdAndName(folder.getCreatedBy().getId(), trimmed)) {
			throw new ValidationException("A folder with this name already exists.", "name");
		}
		folder.setName(trimmed);
		workoutFolderRepository.save(folder);
		return toResponse(folder, workoutRepository.countByFolderId(folder.getId()));
	}

	@Transactional
	public void deleteFolder(WorkoutFolder folder) {
		workoutFolderRepository.delete(folder);
	}

	private String requireName(String name) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty()) {
			throw new ValidationException("This field is required.", "name");
		}
		return trimmed;
	}

	private WorkoutFolderResponse toResponse(WorkoutFolder folder, long count) {
		return new WorkoutFolderResponse(folder.getId(), folder.getName(), count);
	}
}
