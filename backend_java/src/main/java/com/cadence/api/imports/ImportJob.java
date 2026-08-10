package com.cadence.api.imports;

import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row per import run - unlike ExportJob, these are historical (like Upload/UploadBatch), not
 * a single large file to keep managing, so there's no "replace the previous one" constraint. */
@Entity
@Table(name = "import_job")
public class ImportJob extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private ImportStatus status = ImportStatus.QUEUED;

	/** One of ImportReader's fixed section names ("equipment"/"workouts"/"activities"/"races"/
	 * "scheduled_workouts") - null before processing starts. Same contract as ExportJob.currentStep. */
	@Column(name = "current_step")
	private String currentStep;

	@Column(name = "activities_imported", nullable = false)
	private int activitiesImported;

	@Column(name = "races_imported", nullable = false)
	private int racesImported;

	@Column(name = "workouts_imported", nullable = false)
	private int workoutsImported;

	@Column(name = "scheduled_workouts_imported", nullable = false)
	private int scheduledWorkoutsImported;

	@Column(name = "bikes_imported", nullable = false)
	private int bikesImported;

	@Column(name = "shoes_imported", nullable = false)
	private int shoesImported;

	@Column(name = "components_imported", nullable = false)
	private int componentsImported;

	@Column(name = "items_skipped", nullable = false)
	private int itemsSkipped;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@PrePersist
	private void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "imp";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public ImportStatus getStatus() {
		return status;
	}

	public void setStatus(ImportStatus status) {
		this.status = status;
	}

	public String getCurrentStep() {
		return currentStep;
	}

	public void setCurrentStep(String currentStep) {
		this.currentStep = currentStep;
	}

	public int getActivitiesImported() {
		return activitiesImported;
	}

	public void setActivitiesImported(int activitiesImported) {
		this.activitiesImported = activitiesImported;
	}

	public int getRacesImported() {
		return racesImported;
	}

	public void setRacesImported(int racesImported) {
		this.racesImported = racesImported;
	}

	public int getWorkoutsImported() {
		return workoutsImported;
	}

	public void setWorkoutsImported(int workoutsImported) {
		this.workoutsImported = workoutsImported;
	}

	public int getScheduledWorkoutsImported() {
		return scheduledWorkoutsImported;
	}

	public void setScheduledWorkoutsImported(int scheduledWorkoutsImported) {
		this.scheduledWorkoutsImported = scheduledWorkoutsImported;
	}

	public int getBikesImported() {
		return bikesImported;
	}

	public void setBikesImported(int bikesImported) {
		this.bikesImported = bikesImported;
	}

	public int getShoesImported() {
		return shoesImported;
	}

	public void setShoesImported(int shoesImported) {
		this.shoesImported = shoesImported;
	}

	public int getComponentsImported() {
		return componentsImported;
	}

	public void setComponentsImported(int componentsImported) {
		this.componentsImported = componentsImported;
	}

	public int getItemsSkipped() {
		return itemsSkipped;
	}

	public void setItemsSkipped(int itemsSkipped) {
		this.itemsSkipped = itemsSkipped;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}
}
