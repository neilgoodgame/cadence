package com.cadence.api.activities;

import com.cadence.api.activities.dto.StreamsResponse;
import com.cadence.api.security.AccessGuard;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class StreamController {

	private final ActivityService activityService;
	private final StreamService streamService;
	private final AccessGuard accessGuard;
	private final JsonMapper jsonMapper;

	public StreamController(ActivityService activityService, StreamService streamService, AccessGuard accessGuard,
			JsonMapper jsonMapper) {
		this.activityService = activityService;
		this.streamService = streamService;
		this.accessGuard = accessGuard;
		this.jsonMapper = jsonMapper;
	}

	// Written via Jackson's streaming JsonGenerator straight to the response's OutputStream
	// instead of returning a StreamsResponse for the default message converter to serialize -
	// the latter builds the whole JSON text as a second, separate in-memory buffer on top of the
	// field arrays StreamService already assembled, and sends nothing to the client until that's
	// done. Flushing after each field forces its bytes out as soon as they're ready: no
	// Content-Length is set (there's no way to know it up front), so this is a real
	// Transfer-Encoding: chunked response, not just an internal implementation detail.
	@GetMapping("/v1/activities/{id}/streams")
	public void getStreams(@PathVariable String id,
			@RequestParam(required = false) String fields,
			@RequestParam(defaultValue = "high") String resolution,
			HttpServletResponse response) throws IOException {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		StreamsResponse streams = streamService.getStreams(activity, fields, resolution);

		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		try (JsonGenerator generator = jsonMapper.createGenerator(response.getOutputStream())) {
			generator.writeStartObject();
			generator.writeName("object");
			generator.writeString("streams");
			generator.writeName("resolution");
			generator.writeString(streams.resolution());
			generator.writeName("fields");
			generator.writeStartObject();
			for (Map.Entry<String, Object> entry : streams.fields().entrySet()) {
				generator.writeName(entry.getKey());
				jsonMapper.writeValue(generator, entry.getValue());
				generator.flush();
			}
			generator.writeEndObject();
			generator.writeEndObject();
		}
	}
}
