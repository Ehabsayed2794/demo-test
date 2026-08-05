/* ════════════════════════════════════════════════════════════════════
   Estimation — InventoryService (Service Layer skeleton — Sprint 2.7)
   API-ONLY STUB. No Firestore logic, no business logic, no reads/writes.
   See docs/architecture/ServiceArchitecture.md's InventoryService section.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("InventoryService." + methodName + "() is not implemented yet — see docs/architecture/FirestoreSchema.md (inventory/{uid}).");
  }

  function getInventory(uid) { return notImplemented("getInventory"); }
  function purchaseWithSoftCurrency(uid, itemId) { return notImplemented("purchaseWithSoftCurrency"); }
  function equipItem(uid, slot, itemId) { return notImplemented("equipItem"); }

  global.InventoryService = {
    getInventory: getInventory,
    purchaseWithSoftCurrency: purchaseWithSoftCurrency,
    equipItem: equipItem
  };
})(window);
