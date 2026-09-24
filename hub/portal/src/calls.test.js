import assert from "node:assert/strict";
import test from "node:test";
import { callRecordingUrl } from "./api.js";
import {
  attachCallAudio,
  getCallsPlaybackState,
  resetCallPlayback,
  toggleCallPlayback,
} from "./calls.js";

function mockAudio({ play } = {}) {
  return {
    paused: true,
    src: "",
    preload: "",
    play() {
      this.paused = false;
      if (play) return play.call(this);
      return Promise.resolve();
    },
    pause() {
      this.paused = true;
    },
    addEventListener() {},
  };
}

test("callRecordingUrl uses the portal recording contract", () => {
  assert.match(callRecordingUrl("abc/def"), /\/portal\/api\/calls\/abc%2Fdef\/recording$/);
});

test("toggleCallPlayback calls play() in the same turn without fetch", () => {
  const order = [];
  const previousFetch = globalThis.fetch;
  globalThis.fetch = () => {
    order.push("fetch");
    return Promise.resolve({ ok: true });
  };
  const audio = mockAudio({
    play() {
      order.push("play");
      return new Promise((resolve) => {
        queueMicrotask(() => {
          order.push("play-resolved");
          resolve();
        });
      });
    },
  });
  resetCallPlayback();
  attachCallAudio(audio);
  toggleCallPlayback("call-1", audio);
  assert.deepEqual(order, ["play"]);
  assert.equal(getCallsPlaybackState().playingId, "call-1");
  assert.equal(getCallsPlaybackState().playError, "");
  assert.match(audio.src, /\/calls\/call-1\/recording$/);
  globalThis.fetch = previousFetch;
});

test("play rejection clears the fake playing state", async () => {
  const audio = mockAudio({
    play() {
      this.paused = true;
      return Promise.reject(new Error("NotAllowedError"));
    },
  });
  resetCallPlayback();
  attachCallAudio(audio);
  toggleCallPlayback("call-silent", audio);
  assert.equal(getCallsPlaybackState().playingId, "call-silent");
  await new Promise((resolve) => queueMicrotask(resolve));
  assert.equal(getCallsPlaybackState().playingId, null);
  assert.equal(getCallsPlaybackState().playError, "No OpenClaw recording for this call.");
});

test("second tap on the playing card pauses", () => {
  const audio = mockAudio();
  resetCallPlayback();
  attachCallAudio(audio);
  toggleCallPlayback("call-1", audio);
  assert.equal(audio.paused, false);
  toggleCallPlayback("call-1", audio);
  assert.equal(audio.paused, true);
  assert.equal(getCallsPlaybackState().playingId, null);
});
