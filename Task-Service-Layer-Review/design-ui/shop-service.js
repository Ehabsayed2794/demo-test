/* ════════════════════════════════════════════════════════════════════
   Estimation — ShopService (Service Layer skeleton — Sprint 2.7)
   API-ONLY STUB. No Firestore logic, no business logic, no reads/writes.
   See docs/architecture/ServiceArchitecture.md's ShopService section.

   Deliberate exception to the "throw Not implemented" default: these
   are read-only catalog lookups, and a Shop screen built against this
   stub should be able to render an empty/"coming soon" catalog instead
   of crashing — so these return safe empty placeholders (with a
   console.warn) rather than throwing. See docs/implementation/
   ServiceLayer.md for the full policy this file follows.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function getCatalog() {
    console.warn("ShopService.getCatalog() is not implemented yet — returning an empty catalog.");
    return Promise.resolve([]);
  }

  function getItem(itemId) {
    console.warn("ShopService.getItem() is not implemented yet — returning null.");
    return Promise.resolve(null);
  }

  global.ShopService = {
    getCatalog: getCatalog,
    getItem: getItem
  };
})(window);
