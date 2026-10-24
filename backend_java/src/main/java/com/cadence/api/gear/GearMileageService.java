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
}
