package com.cadence.api.gear;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.PayloadTooLargeException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.dto.ShoePhotoResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ShoePhotoService {

	// A phone camera JPEG/HEIC is typically 2-8MB - generous enough for that, while still
	// bounding growth of this table (stored as a blob directly in Postgres, see ShoePhoto's
	// own Javadoc for why that's an acceptable tradeoff at this app's scale).
	static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

	static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/heic", "image/heif");

	private final ShoePhotoRepository shoePhotoRepository;

	public ShoePhotoService(ShoePhotoRepository shoePhotoRepository) {
		this.shoePhotoRepository = shoePhotoRepository;
	}

	public List<ShoePhotoResponse> list(String shoeId) {
		return shoePhotoRepository.findByShoeIdOrderByTakenOnAscCreatedAsc(shoeId).stream().map(this::toResponse).toList();
	}

	@Transactional
	public ShoePhotoResponse upload(Shoe shoe, MultipartFile image, LocalDate takenOn, Integer km, String notes) {
		if (image == null || image.isEmpty()) {
			throw new ValidationException("This field is required.", "image");
		}
		String contentType = image.getContentType();
		if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
			throw new ValidationException("Must be a JPEG, PNG, HEIC or HEIF image.", "image");
		}
		if (image.getSize() > MAX_IMAGE_BYTES) {
			throw new PayloadTooLargeException("Images must be " + (MAX_IMAGE_BYTES / (1024 * 1024)) + "MB or smaller.");
		}

		ShoePhoto photo = new ShoePhoto();
		photo.setShoe(shoe);
		try {
			photo.setImage(image.getBytes());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		photo.setContentType(contentType);
		photo.setTakenOn(takenOn != null ? takenOn : LocalDate.now());
		photo.setKm(km != null ? km : shoe.getKm());
		photo.setNotes(notes);
		return toResponse(shoePhotoRepository.save(photo));
	}

	/** For the image-serving and delete endpoints - both need the owning shoe's athlete loaded
	 * (for the access check) within a session that outlives the controller's own call. */
	public ShoePhoto getWithShoeAndAthlete(String id) {
		return shoePhotoRepository.findByIdWithShoeAndAthlete(id).orElseThrow(() -> new NotFoundException("No such photo."));
	}

	@Transactional
	public void delete(String id) {
		shoePhotoRepository.deleteById(id);
	}

	private ShoePhotoResponse toResponse(ShoePhoto photo) {
		return new ShoePhotoResponse(
				photo.getId(), photo.getShoe().getId(), photo.getContentType(), photo.getTakenOn(), photo.getKm(),
				photo.getNotes(), photo.getCreated());
	}
}
