import { describe, expect, it } from "vitest";
import { parseCsv } from "./csv";

describe("parseCsv", () => {
  it("splits a simple header + rows file", () => {
    expect(parseCsv("Manufacturer,Model,Version\nHOKA,Mach,6\nNike,Vomero,18\n")).toEqual([
      ["Manufacturer", "Model", "Version"],
      ["HOKA", "Mach", "6"],
      ["Nike", "Vomero", "18"],
    ]);
  });

  it("preserves blank trailing fields", () => {
    expect(parseCsv("Manufacturer,Model,Version,Colourway\nAdidas,Evo SL,,\n")).toEqual([
      ["Manufacturer", "Model", "Version", "Colourway"],
      ["Adidas", "Evo SL", "", ""],
    ]);
  });

  it("handles quoted fields with embedded commas and escaped quotes", () => {
    expect(parseCsv('Name,Note\n"Nike, Inc.","She said ""hi"""\n')).toEqual([
      ["Name", "Note"],
      ["Nike, Inc.", 'She said "hi"'],
    ]);
  });

  it("handles a file with no trailing newline", () => {
    expect(parseCsv("Manufacturer,Model\nHOKA,Mach")).toEqual([
      ["Manufacturer", "Model"],
      ["HOKA", "Mach"],
    ]);
  });

  it("drops blank lines", () => {
    expect(parseCsv("Manufacturer,Model\nHOKA,Mach\n\nNike,Vomero\n")).toEqual([
      ["Manufacturer", "Model"],
      ["HOKA", "Mach"],
      ["Nike", "Vomero"],
    ]);
  });
});
