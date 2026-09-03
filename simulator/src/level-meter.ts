import { energyToMeterLevel } from "./audio-level";

/** Horizontal bar meter bound to a `.meter-channel` or `.meter` element in the DOM. */
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
    const track = this.root.querySelector<HTMLElement>(".meter-track");
    track?.setAttribute("aria-valuenow", pct);
    this.root.classList.toggle("meter-silent", this.level < 0.02);
    this.root.classList.toggle("meter-hot", this.level > 0.85);
  }

  reset(): void {
    this.setLevel(0);
  }
}

/** L/R pair of level meters for stereo sanity-checking. */
export class StereoMeter {
  private readonly root: HTMLElement;
  private readonly left: LevelMeter;
  private readonly right: LevelMeter;

  constructor(root: HTMLElement) {
    this.root = root;
    const lEl = root.querySelector<HTMLElement>('.meter-channel[data-channel="l"]');
    const rEl = root.querySelector<HTMLElement>('.meter-channel[data-channel="r"]');
    if (!lEl || !rEl) throw new Error("StereoMeter: missing L/R channels");
    this.left = new LevelMeter(lEl);
    this.right = new LevelMeter(rEl);
  }

  setActive(active: boolean): void {
    this.root.classList.toggle("meter-inactive", !active);
  }

  /** Same energy on both channels (mono source). */
  setEnergy(energy: number): void {
    this.left.setEnergy(energy);
    this.right.setEnergy(energy);
  }

  setStereoEnergy(l: number, r: number): void {
    this.left.setEnergy(l);
    this.right.setEnergy(r);
  }

  /** Drive L/R from normalized 0–1 levels (e.g. hub `channels` field). */
  setStereoLevel(l: number, r: number): void {
    this.left.setLevel(l);
    this.right.setLevel(r);
  }

  reset(): void {
    this.left.reset();
    this.right.reset();
  }
}
