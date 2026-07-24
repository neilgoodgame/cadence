package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.races.dto.RaceCreateRequest;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.races.dto.RaceUpdateRequest;
import com.cadence.api.users.User;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RaceService {

	private final RaceRepository raceRepository;
	private final ActivityRepository activityRepository;

	public RaceService(RaceRepository raceRepository, ActivityRepository activityRepository) {
		this.raceRepository = raceRepository;
		this.activityRepository = activityRepository;
	}

	public List<Race> listRaces(String athleteId) {
		return raceRepository.findByAthleteIdOrderByDateAsc(athleteId);
	}

	public Race getRace(String id) {
		return raceRepository.findById(id).orElseThrow(() -> new NotFoundException("No such race."));
	}

	@Transactional
	public Race createRace(User athlete, RaceCreateRequest req) {
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName(req.name());
		race.setDate(LocalDate.parse(req.date()));
		race.setSport(parseSport(req.sport()));
		race.setDistanceKm(req.distanceKm());
		race.setGoalTime(parseDuration(req.goalTime()));
		race.setResultTime(parseDuration(req.resultTime()));
		race.setActivity(resolveActivity(req.activityId()));
		race.setUrl(req.url());
		race.setResultsUrl(req.resultsUrl());
		race.setNotes(req.notes() != null ? req.notes() : "");
		return raceRepository.save(race);
	}

	@Transactional
	public Race updateRace(Race race, RaceUpdateRequest req) {
		if (req.name() != null) race.setName(req.name());
		if (req.date() != null) race.setDate(LocalDate.parse(req.date()));
		if (req.sport() != null) race.setSport(parseSport(req.sport()));
		if (req.distanceKm() != null) race.setDistanceKm(req.distanceKm());
		if (req.goalTime() != null) race.setGoalTime(parseDuration(req.goalTime()));
		if (req.resultTime() != null) race.setResultTime(parseDuration(req.resultTime()));
		if (req.activityId() != null) race.setActivity(resolveActivity(req.activityId()));
		if (req.url() != null) race.setUrl(req.url().isEmpty() ? null : req.url());
		if (req.resultsUrl() != null) race.setResultsUrl(req.resultsUrl().isEmpty() ? null : req.resultsUrl());
		if (req.notes() != null) race.setNotes(req.notes());
		return raceRepository.save(race);
	}

	@Transactional
	public void deleteRace(String id) {
		raceRepository.deleteById(id);
	}

	public RaceResponse toResponse(Race race) {
		return new RaceResponse(
				race.getId(),
				race.getAthlete().getId(),
				race.getName(),
				race.getDate().toString(),
				race.getSport() != null ? race.getSport().wireValue() : "",
				race.getDistanceKm(),
				formatDuration(race.getGoalTime()),
				formatDuration(race.getResultTime()),
				race.getActivity() != null ? race.getActivity().getId() : null,
				race.getUrl(),
				race.getResultsUrl(),
				race.getNotes()
		);
	}

	// "H:MM:SS" or "HH:MM:SS" → total seconds; null/blank → null
	private static Integer parseDuration(String s) {
		if (s == null || s.isBlank()) return null;
		String[] parts = s.split(":");
		if (parts.length != 3) return null;
		int h = Integer.parseInt(parts[0]);
		int m = Integer.parseInt(parts[1]);
		int sec = Integer.parseInt(parts[2]);
		return h * 3600 + m * 60 + sec;
	}

	// seconds → "H:MM:SS"; null → null
	private static String formatDuration(Integer seconds) {
		if (seconds == null) return null;
		int h = seconds / 3600;
		int m = (seconds % 3600) / 60;
		int s = seconds % 60;
		return String.format("%d:%02d:%02d", h, m, s);
	}

	private static Sport parseSport(String s) {
		if (s == null || s.isBlank()) return null;
		try {
			return Sport.fromWireValue(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private Activity resolveActivity(String activityId) {
		if (activityId == null || activityId.isBlank()) return null;
		return activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
	}
}
