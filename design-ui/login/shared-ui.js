// Shared UI kit: Toast, Modal, Input helpers. Load after table-system.css style tokens
// are in scope (any screen already using --accent/--panel/etc). See SHARED_COMPONENTS.md.
window.UI = (function () {
  function toast(msg, opts) {
    opts = opts || {};
    var host = opts.container || document.body;
    var el = host.querySelector(":scope > .ui-toast") || host.querySelector(".ui-toast");
    if (!el) {
      el = document.createElement("div");
      el.className = "ui-toast";
      // PRODUCTION UX AUDIT (Finding #2): errors/rejections shown via
      // toast were visual-only — a screen reader user got no signal at
      // all. Set once, at creation, never touched by the reuse branch
      // below so re-showing the same element doesn't re-trigger
      // anything unexpected. No effect on timing/styling/positioning.
      el.setAttribute("role", "alert");
      el.setAttribute("aria-live", "assertive");
      host.appendChild(el);
    }
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(el._uiToastT);
    el._uiToastT = setTimeout(function () { el.classList.remove("show"); }, opts.duration || 2200);
    return el;
  }

  function openModal(el) {
    if (!el) return;
    el.hidden = false;
    requestAnimationFrame(function () { el.classList.add("is-open"); });
  }

  function closeModal(el) {
    if (!el) return;
    el.classList.remove("is-open");
    setTimeout(function () { el.hidden = true; }, 160);
  }

  // Wires backdrop-click and [data-close]/.ui-modal-close clicks to close `el`.
  // Call once per modal at setup time.
  function bindModalDismiss(el) {
    if (!el) return;
    el.addEventListener("click", function (e) {
      if (e.target === el || e.target.closest("[data-close]") || e.target.closest(".ui-modal-close")) closeModal(el);
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && !el.hidden) closeModal(el);
    });
  }

  // Sets/clears the .ui-field error state and hint text.
  function setFieldError(fieldEl, message) {
    if (!fieldEl) return;
    var hint = fieldEl.querySelector(".ui-hint");
    if (message) {
      fieldEl.classList.add("error");
      if (hint) hint.textContent = message;
    } else {
      fieldEl.classList.remove("error");
      if (hint) hint.textContent = "";
    }
  }

  return { toast: toast, openModal: openModal, closeModal: closeModal, bindModalDismiss: bindModalDismiss, setFieldError: setFieldError };
})();
