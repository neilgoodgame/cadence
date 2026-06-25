package com.cadence.api.activities.calc;

/**
 * Maps Garmin's 0.0-5.0 aerobic training effect to its benefit label, per Garmin's documented
 * scale: 0.0-0.9 No Benefit, 1.0-1.9 Minor Benefit, 2.0-2.9 Maintaining, 3.0-3.9 Improving,
 * 4.0-4.9 Highly Improving, 5.0 Overreaching.
 */
public final class TrainingEffectLabel {

	public static String of(Double aerobicTrainingEffect) {
		if (aerobicTrainingEffect == null) {
			return "";
		}
		if (aerobicTrainingEffect < 1.0) {
			return "No Benefit";
		}
		if (aerobicTrainingEffect < 2.0) {
			return "Minor Benefit";
		}
		if (aerobicTrainingEffect < 3.0) {
			return "Maintaining";
		}
		if (aerobicTrainingEffect < 4.0) {
			return "Improving";
		}
		if (aerobicTrainingEffect < 5.0) {
			return "Highly Improving";
		}
		return "Overreaching";
	}

	private TrainingEffectLabel() {
	}
}
