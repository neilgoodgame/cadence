package com.cadence.api.gear;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** A dated photo of a shoe's wear - typically the sole, to track tread degradation visually
 * alongside its recorded km. Stored as a blob directly in Postgres rather than object storage -
 * this app has no existing file-upload-and-serve infrastructure to build on, and at the
 * realistic scale here (a handful of athletes, a few photos per shoe) the size cost is
 * negligible. {@code ShoePhotoService.MAX_IMAGE_BYTES} bounds growth per photo. */
@Entity
@Table(name = "shoe_photo")
public class ShoePhoto extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shoe_id", nullable = false)
	private Shoe shoe;

	// Plain byte[], not @Lob - Hibernate maps @Lob to Postgres's separate large-object (oid)
	// storage mechanism, but the V67 migration uses a plain bytea column, which a bare byte[]
	// maps to directly and is simpler for a value this small (bounded to
	// ShoePhotoService.MAX_IMAGE_BYTES, nowhere near bytea's own 1GB ceiling).
	@Column(nullable = false)
	private byte[] image;

	@Column(name = "content_type", nullable = false)
	private String contentType;

	@Column(name = "taken_on", nullable = false)
	private LocalDate takenOn;

	// The shoe's own km at the moment this photo was taken - distinct from Shoe.km (the
	// shoe's current total), which keeps moving after the photo was taken.
	@Column(nullable = false)
	private int km;

	@Column(nullable = false)
	private String notes = "";

	@Column(nullable = false)
	private Instant created;

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "shph";
	}

	public Shoe getShoe() {
		return shoe;
	}

	public void setShoe(Shoe shoe) {
		this.shoe = shoe;
	}

	public byte[] getImage() {
		return image;
	}

	public void setImage(byte[] image) {
		this.image = image;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public LocalDate getTakenOn() {
		return takenOn;
	}

	public void setTakenOn(LocalDate takenOn) {
		this.takenOn = takenOn;
	}

	public int getKm() {
		return km;
	}

	public void setKm(int km) {
		this.km = km;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes != null ? notes : "";
	}

	public Instant getCreated() {
		return created;
	}
}
