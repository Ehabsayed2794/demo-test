const assert = require("assert");
const fs = require("fs");
const vm = require("vm");

const source = fs.readFileSync(require("path").join(__dirname, "..", "design-ui", "shared-i18n.js"), "utf8");
const requiredTerms = {
  Sans: "سانس",
  Spades: "إسبيط",
  Asbeed: "أسبيك",
  Hearts: "كبة",
  Koba: "كبة",
  Diamonds: "ديناري",
  Dinari: "ديناري",
  Clubs: "تريفلة",
  Trefle: "تريفلة",
  Estimation: "استيمشان",
  Dash: "داش",
  Risk: "مخاطرة",
  "Sa'ayda": "صايدة",
  Caller: "الكولر",
  With: "معاه",
  Rematch: "ريفانش",
  Ready: "جاهز"
};

function makeWindow(search, storedLanguage, nodes = []) {
  const document = {
    readyState: "complete",
    dir: "ltr",
    body: {},
    createTreeWalker() {
      let index = 0;
      return { nextNode: () => nodes[index++] || null };
    }
  };
  const window = {
    location: { search },
    localStorage: {
      getItem(key) { return key === "estemshan_lang" ? storedLanguage : null; }
    },
    document,
    NodeFilter: { SHOW_TEXT: 4 }
  };
  vm.runInNewContext(source, { window });
  return { window, document };
}

const dictionaryCase = makeWindow("");
const dictionary = dictionaryCase.window.EstemshanI18n.DICTIONARY;
for (const [term, translation] of Object.entries(requiredTerms)) {
  assert.strictEqual(dictionary[term], translation, `missing or incorrect official term: ${term}`);
}
console.log(`PASS official dictionary terms (${Object.keys(requiredTerms).length})`);
assert.ok(Object.keys(dictionary).length >= Object.keys(requiredTerms).length, "dictionary unexpectedly dropped Login/Lobby keys");
console.log("PASS dictionary contains all required official keys");

assert.strictEqual(makeWindow("?lang=ar", null).window.EstemshanI18n.isArabicEnabled(), true);
assert.strictEqual(makeWindow("?lang=en", "ar").window.EstemshanI18n.isArabicEnabled(), true);
assert.strictEqual(makeWindow("?lang=en", "en").window.EstemshanI18n.isArabicEnabled(), false);
console.log("PASS Arabic query/localStorage flag logic");

const textNodes = [
  { nodeValue: "Estimation Ready", parentNode: { nodeName: "DIV" } },
  { nodeValue: "Caller With", parentNode: { nodeName: "SPAN" } },
  { nodeValue: "Estimation", parentNode: { nodeName: "SCRIPT" } }
];
const applied = makeWindow("?lang=ar", null, textNodes);
assert.strictEqual(applied.document.dir, "rtl");
assert.strictEqual(textNodes[0].nodeValue, "استيمشان جاهز");
assert.strictEqual(textNodes[1].nodeValue, "الكولر معاه");
assert.strictEqual(textNodes[2].nodeValue, "Estimation", "script text must not be translated");
assert.ok(applied.window.EstemshanI18n.apply().replaced >= 0);
console.log("PASS RTL direction and rendered text-node replacement");

console.log("4 passed, 0 failed");
