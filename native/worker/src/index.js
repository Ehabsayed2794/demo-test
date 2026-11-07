// Worker entry point (E3.1 / O-06).
//
// This file is the wrangler entry: it re-exports the application (so the
// default export is the fetch handler) and the MatchDO class (wrangler
// discovers Durable Object classes only from the entry module).
//
// Everything else lives in app.js — it is imported by the tests without
// touching "cloudflare:workers", which only exists inside workerd.

export { default, createWorker, HANDLERS } from "./app.js";
export { MatchDO } from "./match-do.js";
