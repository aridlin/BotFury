"use strict";

class FleetTerrainView {
  static bandOrder = [0, 4, 2, 6, 1, 5, 3, 7];
  static maxChunk = 1875000;
  static detailScale = 8;

  constructor(redraw) {
    this.redraw = redraw;
    this.tiles = [];
    this.details = new Map();
    this.swatches = new Map();
    this.world = "";
    this.query = "";
    this.pending = 0;
    this.lastFetch = 0;
    this.requestSequence = 0;
  }

  update(world, bounds) {
    if (this.world !== world) {
      this.world = world;
      this.tiles = [];
      this.query = "";
      this.requestSequence++;
      clearTimeout(this.pending);
      this.pending = 0;
    }
    if (!world) return;
    const [server, dimension] = JSON.parse(world);
    if (!server) return;
    const [minX, minZ, maxX, maxZ] = bounds.map(block =>
      Math.max(-FleetTerrainView.maxChunk, Math.min(FleetTerrainView.maxChunk, Math.floor(block / 16))));
    const query = new URLSearchParams({server, dimension, minX, minZ, maxX, maxZ}).toString();
    if (query === this.query && performance.now() - this.lastFetch < 1700) return;
    if (query !== this.query) {
      this.query = query;
      clearTimeout(this.pending);
      this.pending = 0;
    }
    if (this.pending) return;
    const generation = ++this.requestSequence;
    this.pending = setTimeout(async () => {
      this.pending = 0;
      try {
        const response = await fetch("api/terrain?" + query);
        if (!response.ok) return;
        const {tiles} = await response.json();
        if (generation !== this.requestSequence) return;
        this.lastFetch = performance.now();
        this.tiles = tiles;
        this.redraw();
      } catch {
        // A temporarily unavailable map will be sampled again with the fleet.
        this.lastFetch = performance.now();
      }
    }, 130);
  }

  static tone(r, g, b, x, z) {
    const lum = r * .2126 + g * .7152 + b * .0722;
    const step = 255 / 7;
    const scaled = lum / step;
    const base = Math.floor(scaled);
    const density = Math.round((scaled - base) * 8);
    const upper = this.bandOrder[(x + z) & 7] < density;
    const delta = (base + Number(upper)) * step - lum;
    return [r + delta, g + delta, b + delta];
  }

  static plate(width, height) {
    const plate = document.createElement("canvas");
    plate.width = width;
    plate.height = height;
    return plate;
  }

  static applyDither(plate, input, originX, originZ) {
    const {width, height} = plate;
    const brush = plate.getContext("2d", {willReadFrequently: true});
    const screen = brush.createImageData(width, height);
    for (let z = 0; z < height; z++) {
      for (let x = 0; x < width; x++) {
        const offset = 4 * (z * width + x);
        const [r, g, b] = [input[offset], input[offset + 1], input[offset + 2]];
        const [red, green, blue] = this.tone(r, g, b, originX + x, originZ + z);
        screen.data[offset] = red;
        screen.data[offset + 1] = green;
        screen.data[offset + 2] = blue;
        screen.data[offset + 3] = 255;
      }
    }
    brush.putImageData(screen, 0, 0);
    return plate;
  }

  swatch(color) {
    if (this.swatches.has(color)) return this.swatches.get(color);
    if (this.swatches.size >= 256) this.swatches.delete(this.swatches.keys().next().value);
    const pixels = new Uint8ClampedArray(8 * 8 * 4);
    for (let offset = 0; offset < pixels.length; offset += 4) {
      pixels[offset] = color >> 16;
      pixels[offset + 1] = (color >> 8) & 255;
      pixels[offset + 2] = color & 255;
    }
    const print = FleetTerrainView.applyDither(FleetTerrainView.plate(8, 8), pixels, 0, 0);
    this.swatches.set(color, print);
    return print;
  }

  detail(tile) {
    const key = JSON.stringify([this.world, tile.x, tile.z, tile.seen]);
    const stored = this.details.get(key);
    if (stored) return stored.image;
    if (this.details.size >= 64) this.details.delete(this.details.keys().next().value);
    const pending = {image: null};
    this.details.set(key, pending);
    const [server, dimension] = JSON.parse(this.world);
    const search = new URLSearchParams({server, dimension, x: tile.x, z: tile.z});
    const source = new Image();
    source.onload = () => {
      const plate = FleetTerrainView.plate(256, 256);
      const brush = plate.getContext("2d", {willReadFrequently: true});
      brush.drawImage(source, 0, 0, 256, 256);
      const {data} = brush.getImageData(0, 0, 256, 256);
      pending.image = FleetTerrainView.applyDither(plate, data, tile.x * 256, tile.z * 256);
      this.redraw();
    };
    source.onerror = () => setTimeout(() => this.details.delete(key), 4000);
    source.src = "api/terrain/tile?" + search;
    return null;
  }

  paint(brush, project, scale, width, height) {
    brush.imageSmoothingEnabled = false;
    const span = 16 * scale;
    for (const tile of this.tiles) {
      const [x, z] = project(tile.x * 16, tile.z * 16);
      if (x > width || z > height || x + span <= 0 || z + span <= 0) continue;
      const [x0, z0, x1, z1] = [x, z, x + span, z + span].map(Math.round);
      const details = scale >= FleetTerrainView.detailScale ? this.detail(tile) : null;
      if (details) {
        brush.drawImage(details, x0, z0, x1 - x0, z1 - z0);
        continue;
      }
      const midX = Math.round(x + span / 2), midZ = Math.round(z + span / 2);
      brush.drawImage(this.swatch(tile.quarters[0]), x0, z0, midX - x0, midZ - z0);
      brush.drawImage(this.swatch(tile.quarters[1]), midX, z0, x1 - midX, midZ - z0);
      brush.drawImage(this.swatch(tile.quarters[2]), x0, midZ, midX - x0, z1 - midZ);
      brush.drawImage(this.swatch(tile.quarters[3]), midX, midZ, x1 - midX, z1 - midZ);
    }
  }
}
