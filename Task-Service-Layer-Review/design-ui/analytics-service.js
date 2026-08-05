/* ════════════════════════════════════════════════════════════════════
   Estimation — AnalyticsService (Service Layer skeleton — Sprint 2.7)
   API-ONLY STUB. No Firestore logic, no network calls, no analytics
   provider wired up yet.

   Deliberate exception to the "throw Not implemented" default:
   analytics must never be able to break a caller — a screen firing
   `AnalyticsService.logEvent(...)` mid-gameplay should never risk an
   uncaught exception over a tracking call. These methods no-op (with a
   console.debug) instead. See docs/implementation/ServiceLayer.md.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function logEvent(name, params) {
    console.debug("[AnalyticsService] logEvent (not implemented, no-op):", name, params || {});
  }

  function setUserProperties(props) {
    console.debug("[AnalyticsService] setUserProperties (not implemented, no-op):", props || {});
  }

  global.AnalyticsService = {
    logEvent: logEvent,
    setUserProperties: setUserProperties
  };
})(window);
