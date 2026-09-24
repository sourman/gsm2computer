import assert from "node:assert/strict";
import test from "node:test";
import { parseRouteFromPath, normalizePath } from "./router.js";

test("normalizePath strips trailing slash", () => {
  assert.equal(normalizePath("/calls/"), "/calls");
  assert.equal(normalizePath("calls"), "/calls");
  assert.equal(normalizePath("/"), "/");
});

test("parseRouteFromPath treats /calls as a top-level page", () => {
  assert.equal(parseRouteFromPath("/calls").page, "calls");
  assert.equal(parseRouteFromPath("/calls/").page, "calls");
  assert.equal(parseRouteFromPath("/messaging").page, "messaging");
  assert.equal(parseRouteFromPath("/messaging/thread/%2B1").page, "messaging");
  assert.equal(parseRouteFromPath("/messaging/thread/%2B1").rest, "/thread/%2B1");
  assert.equal(parseRouteFromPath("/routing").page, "routing");
  assert.equal(parseRouteFromPath("/").page, "home");
});

test("Calls is not a Messaging sub-tab route", () => {
  const messagingCalls = parseRouteFromPath("/messaging/calls");
  assert.equal(messagingCalls.page, "messaging");
  assert.equal(messagingCalls.rest, "/calls");
  assert.notEqual(messagingCalls.page, "calls");
});
