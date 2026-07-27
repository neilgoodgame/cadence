// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { parseZwoFile } from "./workoutImport";

function zwo(workoutBody: string, { name = "Test Workout", sportType = "bike" }: { name?: string; sportType?: string } = {}): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<workout_file>
  <author>Cadence</author>
  <name>${name}</name>
  <description>desc</description>
  <sportType>${sportType}</sportType>
  <tags/>
  <workout>
    ${workoutBody}
  </workout>
</workout_file>`;
}

describe("parseZwoFile", () => {
  it("parses name and sportType", () => {
    const result = parseZwoFile(zwo('<SteadyState Duration="600" Power="0.65"/>', { name: "My Ride", sportType: "bike" }));
    expect(result.name).toBe("My Ride");
    expect(result.sport).toBe("bike");
  });

  it("maps sportType=run to run, and anything else to bike", () => {
    expect(parseZwoFile(zwo('<SteadyState Duration="60" Power="0.5"/>', { sportType: "run" })).sport).toBe("run");
    expect(parseZwoFile(zwo('<SteadyState Duration="60" Power="0.5"/>', { sportType: "swim" })).sport).toBe("bike");
  });

  it("falls back to a default name when <name> is missing or blank", () => {
    const xml = zwo('<SteadyState Duration="60" Power="0.5"/>').replace("<name>Test Workout</name>", "<name></name>");
    expect(parseZwoFile(xml).name).toBe("Imported workout");
  });

  it("parses Warmup and Cooldown as ramps with kind fixed by tag, not power", () => {
    const result = parseZwoFile(zwo('<Warmup Duration="600" PowerLow="0.25" PowerHigh="0.75"/><Cooldown Duration="300" PowerLow="0.6" PowerHigh="0.3"/>'));
    expect(result.steps).toHaveLength(2);
    const [warmup, cooldown] = result.steps;
    expect(warmup).toMatchObject({ kind: "warmup", duration: 600, target_type: "power", target_low: 25, target_high: 75 });
    expect(cooldown).toMatchObject({ kind: "cool", duration: 300, target_type: "power", target_low: 60, target_high: 30 });
  });

  it("parses SteadyState with a single Power attribute as a flat target, classified by the 85% threshold", () => {
    const result = parseZwoFile(zwo('<SteadyState Duration="300" Power="1.05"/><SteadyState Duration="300" Power="0.55"/>'));
    expect(result.steps[0]).toMatchObject({ kind: "block", target_low: 105, target_high: 105 });
    expect(result.steps[1]).toMatchObject({ kind: "rec", target_low: 55, target_high: 55 });
  });

  it("parses Ramp using PowerLow/PowerHigh and classifies by average power", () => {
    const result = parseZwoFile(zwo('<Ramp Duration="180" PowerLow="0.6" PowerHigh="1.2"/>'));
    expect(result.steps[0]).toMatchObject({ kind: "block", duration: 180, target_low: 60, target_high: 120 });
  });

  it("parses FreeRide and MaxEffort as open-target steps", () => {
    const result = parseZwoFile(zwo('<FreeRide Duration="600"/><MaxEffort Duration="30"/>'));
    expect(result.steps[0]).toMatchObject({ kind: "rec", target_type: "open", target_low: null, target_high: null });
    expect(result.steps[1]).toMatchObject({ kind: "block", target_type: "open", target_low: null, target_high: null });
  });

  it("parses IntervalsT into a repeat group of [block, rec]", () => {
    const result = parseZwoFile(zwo('<IntervalsT Repeat="5" OnDuration="240" OffDuration="120" OnPower="1.15" OffPower="0.5"/>'));
    expect(result.steps).toHaveLength(1);
    const group = result.steps[0];
    expect(group.kind).toBe("repeat");
    if (group.kind !== "repeat") throw new Error("expected a repeat group");
    expect(group.repeat).toBe(5);
    expect(group.children).toEqual([
      expect.objectContaining({ kind: "block", duration: 240, target_low: 115, target_high: 115 }),
      expect.objectContaining({ kind: "rec", duration: 120, target_low: 50, target_high: 50 }),
    ]);
  });

  it("parses Cadence into a secondary cadence target, and CadenceResting for IntervalsT's off leg", () => {
    const steady = parseZwoFile(zwo('<SteadyState Duration="300" Power="0.65" Cadence="90"/>')).steps[0];
    expect(steady).toMatchObject({ target2_type: "cadence", target2_low: 90, target2_high: 90 });

    const group = parseZwoFile(
      zwo('<IntervalsT Repeat="3" OnDuration="60" OffDuration="60" OnPower="1.1" OffPower="0.5" Cadence="95" CadenceResting="80"/>'),
    ).steps[0];
    if (group.kind !== "repeat") throw new Error("expected a repeat group");
    expect(group.children[0]).toMatchObject({ target2_low: 95, target2_high: 95 });
    expect(group.children[1]).toMatchObject({ target2_low: 80, target2_high: 80 });
  });

  it("ignores embedded <textevent> tags without producing extra steps or throwing", () => {
    const result = parseZwoFile(
      zwo(`<SteadyState Duration="600" Power="0.42">
        <textevent timeoffset="0" message="Easy warm-up spin."/>
        <textevent timeoffset="300" message="Half way."/>
      </SteadyState>`),
    );
    expect(result.steps).toHaveLength(1);
    expect(result.steps[0]).toMatchObject({ duration: 600, target_low: 42 });
  });

  it("skips unrecognized top-level tags rather than failing the whole import", () => {
    const result = parseZwoFile(zwo('<SteadyState Duration="60" Power="0.5"/><SomeFutureTag Duration="30"/>'));
    expect(result.steps).toHaveLength(1);
  });

  it("throws a clear error for invalid XML", () => {
    expect(() => parseZwoFile("not xml at all <<<")).toThrow(/valid \.zwo XML/);
  });

  it("throws a clear error when there's no <workout> element", () => {
    expect(() => parseZwoFile('<workout_file><name>x</name><sportType>bike</sportType></workout_file>')).toThrow(/No <workout> segments/);
  });

  it("throws a clear error when <workout> contains only unrecognized tags", () => {
    expect(() => parseZwoFile(zwo('<SomeFutureTag Duration="30"/>'))).toThrow(/No recognizable workout segments/);
  });
});
