package com.cadence.api.gear;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.gear.dto.ShoePhotoResponse;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** A shoe's wear-tracking photo timeline - see {@link ShoePhoto}'s own Javadoc for why this is
 * a Postgres blob rather than object storage. {@code /v1/gear/shoes/{id}/photos} for the
 * nested list/create (needs the shoe to resolve ownership and default km); the per-photo
 * image/delete endpoints hang off {@code /v1/gear/shoe-photos/{id}} directly, since a photo id
 * alone is enough once the caller already has it from the list response. */
@RestController
public class ShoePhotoController {

	private final ShoePhotoService shoePhotoService;
	private final ShoeService shoeService;
	private final AccessGuard accessGuard;

	public ShoePhotoController(ShoePhotoService shoePhotoService, ShoeService shoeService, AccessGuard accessGuard) {
		this.shoePhotoService = shoePhotoService;
		this.shoeService = shoeService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/gear/shoes/{id}/photos")
	public DataListResponse<ShoePhotoResponse> listPhotos(@PathVariable String id) {
		Shoe shoe = shoeService.getShoe(id);
		accessGuard.requireRead(shoe.getAthlete().getId());
		return new DataListResponse<>(shoePhotoService.list(id));
	}

	@PostMapping(value = "/v1/gear/shoes/{id}/photos", consumes = "multipart/form-data")
	@ResponseStatus(HttpStatus.CREATED)
	public ShoePhotoResponse uploadPhoto(
			@PathVariable String id,
			@RequestPart("image") MultipartFile image,
			@RequestParam(name = "taken_on", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takenOn,
			@RequestParam(required = false) Integer km,
			@RequestParam(required = false) String notes) {
		Shoe shoe = shoeService.getShoe(id);
		accessGuard.requireWrite(shoe.getAthlete().getId());
		return shoePhotoService.upload(shoe, image, takenOn, km, notes);
	}

	@GetMapping("/v1/gear/shoe-photos/{id}/image")
	public ResponseEntity<byte[]> getImage(@PathVariable String id) {
		ShoePhoto photo = shoePhotoService.getWithShoeAndAthlete(id);
		accessGuard.requireRead(photo.getShoe().getAthlete().getId());
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, photo.getContentType()).body(photo.getImage());
	}

	@DeleteMapping("/v1/gear/shoe-photos/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePhoto(@PathVariable String id) {
		ShoePhoto photo = shoePhotoService.getWithShoeAndAthlete(id);
		accessGuard.requireWrite(photo.getShoe().getAthlete().getId());
		shoePhotoService.delete(id);
	}
}
