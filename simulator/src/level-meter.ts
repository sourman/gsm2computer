import { energyToMeterLevel } from "./audio-level";

/** Horizontal bar meter bound to a `.meter` element in the DOM. */
export class LevelMeter {
  private readonly root: HTMLElement;
  private readonly fill: HTMLElement;
  private level = 0;

  constructor(root: HTMLElement) {
    this.root = root;
    const fill = root.querySelector<HTMLElement>(".meter-fill");
    if (!fill) throw new Error("LevelMeter: missing .meter-fill");
    this.fill = fill;
  }

  /** Dim the meter when its channel is not in use for this call mode. */
  setActive(active: boolean): void {
    this.root.classList.toggle("meter-inactive", !active);
  }

  /** Update from raw μ-law RMS energy (see `mulawEnergy`). */
  setEnergy(energy: number): void {
    this.setLevel(energyToMeterLevel(energy));
  }

  /** Update from a normalized 0–1 level. */
  setLevel(level: number): void {
    this.level = Math.max(0, Math.min(1, level));
    const pct = (this.level * 100).toFixed(1);
    this.fill.style.width = `${pct}%`;
    this.root.classList.toggle("meter-silent", this.level < 0.02);
    this.root.classList.toggle("meter-hot", this.level > 0.85);
  }

  reset(): void {
    this.setLevel(0);
  }
}
