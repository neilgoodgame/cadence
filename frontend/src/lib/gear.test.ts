import { describe, expect, it } from "vitest";
import { sortShoesByManufacturerAndName } from "./gear";

function shoe(manufacturer: string, name: string) {
  return { manufacturer, name };
}

describe("sortShoesByManufacturerAndName", () => {
  it("orders by manufacturer first, then name", () => {
    const shoes = [shoe("Nike", "Zoom Fly"), shoe("Hoka", "Rocket X"), shoe("Nike", "Pegasus")];

    expect(sortShoesByManufacturerAndName(shoes).map((s) => `${s.manufacturer} ${s.name}`)).toEqual([
      "Hoka Rocket X",
      "Nike Pegasus",
      "Nike Zoom Fly",
    ]);
  });

  it("does not mutate the input array", () => {
    const shoes = [shoe("Nike", "Zoom Fly"), shoe("Hoka", "Rocket X")];
    const original = [...shoes];

    sortShoesByManufacturerAndName(shoes);

    expect(shoes).toEqual(original);
  });
});
