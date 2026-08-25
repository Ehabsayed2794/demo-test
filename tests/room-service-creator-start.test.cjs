const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const source = fs.readFileSync("design-ui/room-service.js", "utf8");
const rooms = {
  "creator-room": {
    creator: "creator",
    players: ["creator", "joiner"],
    readyPlayers: ["creator"],
    status: "waiting",
    name: "Creator-only start test"
  }
};
const startCalls = [];
let currentUid = "joiner";

function refFor(id) {
  return {
    id,
    get() {
      const value = rooms[id];
      return Promise.resolve({
        exists: !!value,
        data: () => value ? { ...value, players: [...value.players], readyPlayers: [...value.readyPlayers] } : undefined
      });
    }
  };
}

const context = {
  console,
  Promise,
  window: null,
  firebase: { firestore: { FieldValue: { serverTimestamp: () => ({ __sentinel: "serverTimestamp" }) } } },
  Db: {
    collection(name) {
      assert.equal(name, "rooms");
      return { doc: id => refFor(id) };
    },
    runTransaction(callback) {
      let pending = null;
      const tx = {
        get: ref => ref.get(),
        update: (ref, patch) => { pending = { id: ref.id, patch }; }
      };
      return Promise.resolve(callback(tx)).then(result => {
        if (pending) rooms[pending.id] = { ...rooms[pending.id], ...pending.patch };
        return result;
      });
    }
  },
  PlayerService: {
    updatePlayerProfile: () => Promise.resolve()
  },
  SessionService: {
    getCurrentUser: () => ({ uid: currentUid }),
    refresh: () => Promise.resolve()
  },
  MatchService: {
    startMatch: roomId => {
      startCalls.push(roomId);
      return Promise.resolve("match-" + startCalls.length);
    }
  }
};
context.window = context;
vm.runInNewContext(source, context, { filename: "design-ui/room-service.js" });

(async () => {
  const nonCreatorResult = await context.RoomService.setReady("creator-room", "joiner", true);
  assert.equal(startCalls.length, 0, "a ready non-creator must not attempt match creation");
  assert.equal(nonCreatorResult.matchStart.allReady, true);
  assert.equal(nonCreatorResult.matchStart.started, false);
  assert.equal(nonCreatorResult.matchStart.matchId, null);
  assert.equal(nonCreatorResult.matchStart.error, null);

  currentUid = "creator";
  const creatorResult = await context.RoomService.setReady("creator-room", "creator", true);
  assert.deepEqual(startCalls, ["creator-room"], "the creator still performs the one allowed startMatch call");
  assert.equal(creatorResult.matchStart.started, true);
  assert.equal(creatorResult.matchStart.matchId, "match-1");

  rooms["creator-room"].matchId = "already-started";
  currentUid = "joiner";
  const observingResult = await context.RoomService.setReady("creator-room", "joiner", true);
  assert.deepEqual(startCalls, ["creator-room"], "a non-creator observing matchId must not retry startMatch");
  assert.equal(observingResult.matchStart.started, true);
  assert.equal(observingResult.matchStart.matchId, "already-started");

  console.log("PASS creator-only startMatch orchestration");
  console.log("3 passed, 0 failed");
})().catch(error => {
  console.error("FAIL creator-only startMatch orchestration");
  console.error(error);
  process.exitCode = 1;
});
