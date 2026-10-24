package com.cadence.api.mcp.tools.gear;

import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeKind;
import com.cadence.api.gear.GearService;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeCatalogService;
import com.cadence.api.gear.ShoeService;
import com.cadence.api.gear.dto.BikeCreateRequest;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.BikeUpdateRequest;
import com.cadence.api.gear.dto.ShoeCatalogEntryResponse;
import com.cadence.api.gear.dto.ShoeCreateRequest;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.gear.dto.ShoeUpdateRequest;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link GearService}/{@link ShoeService}/{@link ShoeCatalogService} the
 * REST controllers do - thin wrappers, same pattern as {@code WorkoutWriteTools}. */
@Component
public class GearWriteTools {

	private final GearService gearService;
	private final ShoeService shoeService;
	private final ShoeCatalogService shoeCatalogService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public GearWriteTools(GearService gearService, ShoeService shoeService, ShoeCatalogService shoeCatalogService,
			UserService userService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.gearService = gearService;
		this.shoeService = shoeService;
		this.shoeCatalogService = shoeCatalogService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "create_bike", description = "Add a bike to the athlete's gear library. kind "
			+ "is one of road, indoor, gravel, tt (optional); groupset is freeform (e.g. "
			+ "\"Shimano Ultegra Di2\"); distanceKm seeds its lifetime distance, defaulting to 0.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public BikeResponse createBike(
			@McpToolParam(description = "Bike name", required = true) String name,
			@McpToolParam(description = "road, indoor, gravel, or tt", required = false) String kind,
			@McpToolParam(description = "Groupset, e.g. \"Shimano Ultegra Di2\"", required = false) String groupset,
			@McpToolParam(description = "Lifetime distance in km, defaults to 0", required = false) Integer distanceKm) {
		authorizer.requireScope(McpScopes.GEAR_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		BikeCreateRequest request = new BikeCreateRequest(name, parseBikeKind(kind), groupset, distanceKm);
		Bike bike = gearService.createBike(athlete, request);
		return gearService.toBikeResponse(bike);
	}

	@McpTool(name = "update_bike", description = "Update a bike's name/kind/groupset/lifetime "
			+ "distance - only the fields you pass are changed.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public BikeResponse updateBike(
			@McpToolParam(description = "The bike's id", required = true) String bikeId,
			@McpToolParam(description = "New name, if changing it", required = false) String name,
			@McpToolParam(description = "New kind: road, indoor, gravel, or tt", required = false) String kind,
			@McpToolParam(description = "New groupset, if changing it", required = false) String groupset,
			@McpToolParam(description = "New lifetime distance in km, if changing it", required = false) Integer distanceKm) {
		authorizer.requireScope(McpScopes.GEAR_WRITE);
		Bike bike = gearService.getBike(bikeId);
		accessGuard.requireWrite(bike.getAthlete().getId());

		BikeUpdateRequest request = new BikeUpdateRequest(name, parseBikeKind(kind), groupset, distanceKm);
		Bike updated = gearService.updateBike(bike, request);
		return gearService.toBikeResponse(updated);
	}

	@McpTool(name = "delete_bike", description = "Permanently delete a bike from the gear "
			+ "library. Any tracked wear components are deleted too; activities previously "
			+ "linked to it keep their history but lose the link. This cannot be undone.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
	public void deleteBike(@McpToolParam(description = "The bike's id", required = true) String bikeId) {
		authorizer.requireScope(McpScopes.GEAR_WRITE);
		Bike bike = gearService.getBike(bikeId);
		accessGuard.requireWrite(bike.getAthlete().getId());
		gearService.deleteBike(bikeId);
	}

	@McpTool(name = "create_shoe", description = "Add a pair of shoes to the athlete's gear "
			+ "library. shoeModelVersionId comes from search_shoe_catalog. name defaults to "
			+ "\"<manufacturer> <model> <version> <colourway>\" and must be unique per athlete; "
			+ "limitKm defaults to the athlete's own default shoe limit preference.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ShoeResponse createShoe(
			@McpToolParam(description = "From search_shoe_catalog", required = true) String shoeModelVersionId,
			@McpToolParam(description = "e.g. \"Volt / Black\"", required = true) String colourway,
			@McpToolParam(description = "Defaults to manufacturer + model + version + colourway", required = false) String name,
			@McpToolParam(description = "Retirement distance in km, defaults to the athlete's preference", required = false) Integer limitKm,
			@McpToolParam(description = "Image URL, if any", required = false) String image) {
		authorizer.requireScope(McpScopes.GEAR_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		ShoeCreateRequest request = new ShoeCreateRequest(shoeModelVersionId, colourway, name, limitKm, image);
		Shoe shoe = shoeService.createShoe(athlete, request);
		return shoeService.toResponse(shoe);
	}

	@McpTool(name = "update_shoe", description = "Update a pair of shoes - only the fields you "
			+ "pass are changed. retired=true soft-archives it (excluded from list_shoes) "
			+ "without deleting its history.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ShoeResponse updateShoe(
			@McpToolParam(description = "The shoe's id", required = true) String shoeId,
			@McpToolParam(description = "New name, if changing it", required = false) String name,
			@McpToolParam(description = "New retirement distance in km, if changing it", required = false) Integer limitKm,
			@McpToolParam(description = "New distance logged in km, if changing it", required = false) Integer km,
			@McpToolParam(description = "New image URL, if changing it", required = false) String image,
			@McpToolParam(description = "Soft-archive (true) or restore (false), if changing it", required = false) Boolean retired) {
		authorizer.requireScope(McpScopes.GEAR_WRITE);
		Shoe shoe = shoeService.getShoe(shoeId);
		accessGuard.requireWrite(shoe.getAthlete().getId());

		ShoeUpdateRequest request = new ShoeUpdateRequest(name, limitKm, km, image, retired);
		return shoeService.updateShoeAndRespond(shoeId, request);
	}

	@McpTool(name = "delete_shoe", description = "Permanently delete a pair of shoes from the "
			+ "gear library, including its wear photos. Activities previously linked to it keep "
			+ "their history but lose the link. This cannot be undone.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
	public void deleteShoe(@McpToolParam(description = "The shoe's id", required = true) String shoeId) {
		authorizer.requireScope(McpScopes.GEAR_WRITE);
		Shoe shoe = shoeService.getShoe(shoeId);
		accessGuard.requireWrite(shoe.getAthlete().getId());
		shoeService.deleteShoe(shoeId);
	}

	@McpTool(name = "create_shoe_catalog_entry", description = "Add a new manufacturer/model/"
			+ "version to the shared shoe catalog (used by search_shoe_catalog and create_shoe), "
			+ "for when nothing existing matches. The catalog is shared across every athlete - "
			+ "search first to avoid creating a near-duplicate entry.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ShoeCatalogEntryResponse createShoeCatalogEntry(
			@McpToolParam(description = "e.g. \"Nike\"", required = true) String manufacturer,
			@McpToolParam(description = "e.g. \"Vaporfly\"", required = true) String model,
			@McpToolParam(description = "e.g. \"3\", if this generation has one", required = false) String version) {
		authorizer.requireScope(McpScopes.GEAR_WRITE);
		if (manufacturer == null || manufacturer.isBlank()) {
			throw new ValidationException("manufacturer is required.", "manufacturer");
		}
		if (model == null || model.isBlank()) {
			throw new ValidationException("model is required.", "model");
		}
		String athleteId = accessGuard.effectiveAthleteId();
		return shoeCatalogService.createShoeModel(athleteId, manufacturer, model, version);
	}

	private static BikeKind parseBikeKind(String kind) {
		if (kind == null || kind.isBlank()) {
			return null;
		}
		try {
			return BikeKind.fromWireValue(kind);
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("kind must be one of: road, indoor, gravel, tt.", "kind");
		}
	}
}
