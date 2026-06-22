import { BikesSection } from "./gear/BikesSection";
import { ShoesSection } from "./gear/ShoesSection";

export function GearScreen() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 36 }}>
      <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Gear</h1>
      <BikesSection />
      <ShoesSection />
    </div>
  );
}
