package com.cadence.api.gear;

import com.cadence.api.gear.dto.ComponentCreateRequest;
import com.cadence.api.gear.dto.ComponentResponse;
import com.cadence.api.gear.dto.ComponentUpdateRequest;
import com.cadence.api.gear.dto.ServiceRecordCreateRequest;
import com.cadence.api.gear.dto.ServiceRecordResponse;
import com.cadence.api.security.AccessGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ComponentController {

	private final GearService gearService;
	private final AccessGuard accessGuard;
	private final GearMapper gearMapper;

	public ComponentController(GearService gearService, AccessGuard accessGuard, GearMapper gearMapper) {
		this.gearService = gearService;
		this.accessGuard = accessGuard;
		this.gearMapper = gearMapper;
	}

	@PostMapping("/v1/gear/bikes/{bikeId}/components")
	@ResponseStatus(HttpStatus.CREATED)
	public ComponentResponse createComponent(@PathVariable String bikeId, @Valid @RequestBody ComponentCreateRequest request) {
		Bike bike = gearService.getBike(bikeId);
		accessGuard.requireWrite(bike.getAthlete().getId());
		Component component = gearService.createComponent(bike, request);
		return gearMapper.toResponse(component);
	}

	@PatchMapping("/v1/gear/components/{id}")
	public ComponentResponse updateComponent(@PathVariable String id, @RequestBody ComponentUpdateRequest request) {
		Component component = gearService.getComponent(id);
		accessGuard.requireWrite(component.getBike().getAthlete().getId());
		Component updated = gearService.updateComponent(component, request);
		return gearMapper.toResponse(updated);
	}

	@DeleteMapping("/v1/gear/components/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteComponent(@PathVariable String id) {
		Component component = gearService.getComponent(id);
		accessGuard.requireWrite(component.getBike().getAthlete().getId());
		gearService.deleteComponent(id);
	}

	@PostMapping("/v1/gear/components/{componentId}/service")
	@ResponseStatus(HttpStatus.CREATED)
	public ServiceRecordResponse logService(@PathVariable String componentId, @RequestBody(required = false) ServiceRecordCreateRequest request) {
		Component component = gearService.getComponent(componentId);
		accessGuard.requireWrite(component.getBike().getAthlete().getId());
		ServiceRecordCreateRequest body = request != null ? request : new ServiceRecordCreateRequest(null, null, null, null);
		return gearMapper.toResponse(gearService.logService(component, body));
	}
}
