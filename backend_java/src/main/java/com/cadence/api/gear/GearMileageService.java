package com.cadence.api.gear;

import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Keeps Bike.distanceKm/Shoe.km in sync with the activities currently linked to them.
 *
 * Both fields are also directly, manually editable (the /gear screen, CSV import, data
 * export/import) - this only ever applies a delta on top of whatever's there, it never treats
 * the field as purely computed.
 */
@Service
public class GearMileageService {

	private final BikeRepository bikeRepository;
	private final ShoeRepository shoeRepository;

	public GearMileageService(BikeRepository bikeRepository, ShoeRepository shoeRepository) {
		this.bikeRepository = bikeRepository;
		this.shoeRepository = shoeRepository;
	}

	private void applyBikeDelta(String bikeId, int delta) {
		if (bikeId == null || delta == 0) {
			return;
		}
		Bike bike = bikeRepository.findById(bikeId).orElseThrow();
		bike.setDistanceKm(bike.getDistanceKm() + delta);
		bikeRepository.save(bike);
	}

	private void applyShoeDelta(String shoeId, int delta) {
		if (shoeId == null || delta == 0) {
			return;
		}
		Shoe shoe = shoeRepository.findById(shoeId).orElseThrow();
		shoe.setKm(shoe.getKm() + delta);
		shoeRepository.save(shoe);
	}

	/** Call whenever an activity's bike link changes (set, cleared, or swapped) - moves the
	 * activity's distance off the old bike and onto the new one. A no-op if the id didn't change. */
	public void applyBikeChange(String oldBikeId, String newBikeId, double distanceKm) {
		if (Objects.equals(oldBikeId, newBikeId)) {
			return;
		}
		int delta = Math.round((float) distanceKm);
		applyBikeDelta(oldBikeId, -delta);
		applyBikeDelta(newBikeId, delta);
	}

	/** Same as applyBikeChange, for a shoe link. */
	public void applyShoeChange(String oldShoeId, String newShoeId, double distanceKm) {
		if (Objects.equals(oldShoeId, newShoeId)) {
			return;
		}
		int delta = Math.round((float) distanceKm);
		applyShoeDelta(oldShoeId, -delta);
		applyShoeDelta(newShoeId, delta);
	}

	/** Issue #498: a duplicate-marked activity's distance must not double-count on its bike/
	 * shoe's mileage total alongside its primary's. Mirrors clearing the bike/shoe link for
	 * mileage purposes when an activity becomes a duplicate, and restoring it when it stops
	 * being one - but the link itself is left untouched, so un-marking restores the exact same
	 * bike/shoe without the caller re-specifying it. A no-op if duplicate status didn't
	 * actually change (e.g. unmarkDuplicate called on an activity that wasn't a duplicate).
	 * Takes the raw bike/shoe ids and distance rather than an Activity, matching
	 * applyBikeChange/applyShoeChange above - keeps this service from depending on the
	 * activities package. */
	public void applyDuplicateChange(String bikeId, String shoeId, double distanceKm, boolean wasDuplicate, boolean isDuplicate) {
		if (wasDuplicate == isDuplicate) {
			return;
		}
		if (isDuplicate) {
			applyBikeChange(bikeId, null, distanceKm);
			applyShoeChange(shoeId, null, distanceKm);
		}
		else {
			applyBikeChange(null, bikeId, distanceKm);
			applyShoeChange(null, shoeId, distanceKm);
		}
	}
}
