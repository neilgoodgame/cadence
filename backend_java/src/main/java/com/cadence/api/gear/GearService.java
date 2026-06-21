package com.cadence.api.gear;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.gear.dto.BikeCreateRequest;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.BikeUpdateRequest;
import com.cadence.api.gear.dto.ComponentCreateRequest;
import com.cadence.api.gear.dto.ComponentUpdateRequest;
import com.cadence.api.gear.dto.ServiceRecordCreateRequest;
import com.cadence.api.users.User;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GearService {

	private final BikeRepository bikeRepository;
	private final ComponentRepository componentRepository;
	private final ServiceRecordRepository serviceRecordRepository;

	public GearService(BikeRepository bikeRepository, ComponentRepository componentRepository,
			ServiceRecordRepository serviceRecordRepository) {
		this.bikeRepository = bikeRepository;
		this.componentRepository = componentRepository;
		this.serviceRecordRepository = serviceRecordRepository;
	}

	public List<Bike> listBikes(String athleteId) {
		return bikeRepository.findByAthleteIdOrderByIdDesc(athleteId);
	}

	public BikeResponse toBikeResponse(Bike bike) {
		int count = componentRepository.countByBikeId(bike.getId());
		return new BikeResponse(bike.getId(), bike.getAthlete().getId(), bike.getName(), bike.getKind(),
				bike.getGroupset(), bike.getDistanceKm(), bike.getHours(), bike.getRides(), count);
	}

	@Transactional
	public Bike createBike(User athlete, BikeCreateRequest request) {
		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName(request.name());
		bike.setKind(request.kind());
		bike.setGroupset(request.groupset() != null ? request.groupset() : "");
		bike.setDistanceKm(request.distanceKm() != null ? request.distanceKm() : 0);
		return bikeRepository.save(bike);
	}

	public Bike getBike(String id) {
		return bikeRepository.findById(id).orElseThrow(() -> new NotFoundException("No such bike."));
	}

	@Transactional
	public Bike updateBike(Bike bike, BikeUpdateRequest request) {
		if (request.name() != null) {
			bike.setName(request.name());
		}
		if (request.kind() != null) {
			bike.setKind(request.kind());
		}
		if (request.groupset() != null) {
			bike.setGroupset(request.groupset());
		}
		if (request.distanceKm() != null) {
			bike.setDistanceKm(request.distanceKm());
		}
		return bikeRepository.save(bike);
	}

	@Transactional
	public void deleteBike(String id) {
		bikeRepository.deleteById(id);
	}

	public List<Component> listComponents(String bikeId) {
		return componentRepository.findByBikeId(bikeId);
	}

	@Transactional
	public Component createComponent(Bike bike, ComponentCreateRequest request) {
		Component component = new Component();
		component.setBike(bike);
		component.setName(request.name());
		component.setLimitKm(request.limitKm());
		component.setKm(request.km() != null ? request.km() : 0);
		component.setModel(request.model() != null ? request.model() : "");
		return componentRepository.save(component);
	}

	public Component getComponent(String id) {
		return componentRepository.findByIdWithBikeAndAthlete(id).orElseThrow(() -> new NotFoundException("No such component."));
	}

	@Transactional
	public Component updateComponent(Component component, ComponentUpdateRequest request) {
		if (request.name() != null) {
			component.setName(request.name());
		}
		if (request.limitKm() != null) {
			component.setLimitKm(request.limitKm());
		}
		if (request.km() != null) {
			component.setKm(request.km());
		}
		if (request.model() != null) {
			component.setModel(request.model());
		}
		return componentRepository.save(component);
	}

	@Transactional
	public void deleteComponent(String id) {
		componentRepository.deleteById(id);
	}

	/** {@code reset} (the default) zeroes the component's accumulated distance, e.g. a fresh chain. */
	@Transactional
	public ServiceRecord logService(Component component, ServiceRecordCreateRequest request) {
		ServiceRecord record = new ServiceRecord();
		record.setComponent(component);
		record.setAction(request.action());
		boolean reset = request.reset() == null || request.reset();
		record.setReset(reset);
		record.setNote(request.note() != null ? request.note() : "");
		record.setDate(request.date() != null ? request.date() : LocalDate.now());
		serviceRecordRepository.save(record);
		if (reset) {
			component.setKm(0);
			componentRepository.save(component);
		}
		return record;
	}
}
