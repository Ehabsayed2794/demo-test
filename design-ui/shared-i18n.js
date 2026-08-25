/*
 * Estemshan Arabic i18n slice (M2-PRE/W4).
 *
 * This file is additive and does not alter any screen source. The hosting
 * assembly appends a tiny loader tag to the generated Login and Lobby copies
 * after their existing scripts, so this module runs after the page markup is
 * present. It intentionally uses one DOMContentLoaded/text-node pass and no
 * MutationObserver; dynamic strings rendered after that pass are outside
 * this slice and remain documented as a limitation.
 */
(function (global) {
  "use strict";

  var DICTIONARY = Object.freeze({
    // Official game terms.
    "Sans": "سانس",
    "Spades": "إسبيط",
    "Asbeed": "أسبيك",
    "Hearts": "كبة",
    "Koba": "كبة",
    "Diamonds": "ديناري",
    "Dinari": "ديناري",
    "Clubs": "تريفلة",
    "Trefle": "تريفلة",
    "Estimation": "استيمشان",
    "Dash": "داش",
    "Risk": "مخاطرة",
    "Sa'ayda": "صايدة",
    "Caller": "الكولر",
    "With": "معاه",
    "Rematch": "ريفانش",
    "Ready": "جاهز",

    // Login and Lobby surface strings in this slice.
    "Display Name": "اسم العرض",
    "Create Account": "إنشاء حساب",
    "Sign In": "تسجيل الدخول",
    "Email": "البريد الإلكتروني",
    "Password": "كلمة المرور",
    "Forgot password?": "هل نسيت كلمة المرور؟",
    "Continue with Google": "المتابعة باستخدام Google",
    "Continue as Guest": "المتابعة كضيف",
    "The Competitive Egyptian Strategy Card Game": "لعبة الورق المصرية التنافسية",
    "Play with Friends": "اللعب مع الأصدقاء",
    "Create Room": "إنشاء غرفة",
    "Join a room by ID": "الانضمام إلى غرفة بالمعرّف",
    "Toggle Ready (current room)": "تبديل الجاهزية (الغرفة الحالية)",
    "Ranked Match": "مباراة تصنيفية",
    "Find Match": "البحث عن مباراة",
    "Play vs AI": "اللعب ضد الذكاء الاصطناعي",
    "Choose Level": "اختيار المستوى",
    "Daily Missions": "المهام اليومية",
    "Win 3 Ranked matches": "الفوز بثلاث مباريات تصنيفية",
    "Make 10 exact calls": "تنفيذ عشر نداءات دقيقة"
  });

  function isArabicEnabled() {
    var search = (global.location && global.location.search) || "";
    if (/[?&]lang=ar(?:&|$)/i.test(search)) return true;
    try {
      return global.localStorage && global.localStorage.getItem("estemshan_lang") === "ar";
    } catch (e) {
      return false;
    }
  }

  function replaceTextNodes() {
    if (!global.document || !global.document.body || !global.document.createTreeWalker) return 0;
    var walker = global.document.createTreeWalker(
      global.document.body,
      global.NodeFilter ? global.NodeFilter.SHOW_TEXT : 4,
      null
    );
    var node;
    var count = 0;
    var keys = Object.keys(DICTIONARY).sort(function (a, b) { return b.length - a.length; });
    while ((node = walker.nextNode())) {
      var parent = node.parentNode;
      if (parent && /^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA)$/i.test(parent.nodeName || "")) continue;
      var value = node.nodeValue;
      if (!value || !value.trim()) continue;
      var next = value;
      keys.forEach(function (key) {
        next = next.split(key).join(DICTIONARY[key]);
      });
      if (next !== value) {
        node.nodeValue = next;
        count += 1;
      }
    }
    return count;
  }

  function apply() {
    if (!isArabicEnabled()) return { enabled: false, replaced: 0 };
    if (global.document) global.document.dir = "rtl";
    return { enabled: true, replaced: replaceTextNodes() };
  }

  global.EstemshanI18n = Object.freeze({
    DICTIONARY: DICTIONARY,
    isArabicEnabled: isArabicEnabled,
    replaceTextNodes: replaceTextNodes,
    apply: apply
  });

  if (global.document) {
    if (global.document.readyState === "loading") {
      global.document.addEventListener("DOMContentLoaded", apply, { once: true });
    } else {
      apply();
    }
  }
})(window);
