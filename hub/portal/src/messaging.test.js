import assert from "node:assert/strict";
import test from "node:test";
import { pickLatestThreadPeer, resolveMessagingPeer, parseMessagingRest, getMessagingContext, scrollThreadToLatest, unreadTotal } from "./messaging.js";

test("pickLatestThreadPeer uses newest lastAt", () => {
  const peer = pickLatestThreadPeer([
    { peer: "+1old", lastAt: "2026-01-01T00:00:00Z" },
    { peer: "+1new", lastAt: "2026-09-19T12:00:00Z" },
    { peer: "+1mid", lastAt: "2026-03-01T00:00:00Z" },
  ]);
  assert.equal(peer, "+1new");
});

test("pickLatestThreadPeer empty", () => {
  assert.equal(pickLatestThreadPeer([]), null);
  assert.equal(pickLatestThreadPeer(null), null);
});

test("resolveMessagingPeer auto-opens newest when none selected", () => {
  const threads = [
    { peer: "+1old", lastAt: "2026-01-01T00:00:00Z" },
    { peer: "+1new", lastAt: "2026-09-19T12:00:00Z" },
  ];
  assert.equal(
    resolveMessagingPeer({ peer: null, suppressAutoOpen: false, threads }),
    "+1new",
  );
  assert.equal(
    resolveMessagingPeer({ peer: null, suppressAutoOpen: true, threads }),
    null,
  );
  assert.equal(
    resolveMessagingPeer({ peer: "+1old", suppressAutoOpen: false, threads }),
    "+1old",
  );
});

test("unreadTotal sums badge counts", () => {
  assert.equal(unreadTotal({ a: 1, b: 2 }), 3);
  assert.equal(unreadTotal({}), 0);
});

test("scrollThreadToLatest jumps to scrollHeight", () => {
  const el = { scrollHeight: 480, scrollTop: 12 };
  scrollThreadToLatest(el);
  assert.equal(el.scrollTop, 480);
});

test("parseMessagingRest opens a thread path and leaves bare / for auto-open", () => {
  parseMessagingRest(`/thread/${encodeURIComponent("+1555")}`);
  assert.equal(getMessagingContext().peer, "+1555");
  parseMessagingRest("/");
  assert.equal(getMessagingContext().peer, null);
});
