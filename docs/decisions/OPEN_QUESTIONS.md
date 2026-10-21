# Open-questions register (as of 2026-10-21)

Companion to `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`. Statuses:
**DECIDED** · **RESOLVED-BY-RECORDS/CODE** · **OWNER** (needs the owner) · **TECH** (Claude measures it) ·
**DEFERRED** (out of V1). Nothing here is owner-approved unless it says DECIDED. Questions are in
Egyptian Arabic; priority P1 blocks Stage 1 design, P2 blocks a later phase.

## A. Owner questions

| ID | Pri | السؤال | ليه مهم | الاختيارات / توصية | المصدر |
|---|---|---|---|---|---|
| Q2e | P1 | بعد قاعدة الـKoz المشترك: الـVote Kick (كان محتاج Koz واحد بس) يشتغل على كذا Koz؟ وأي ترتيب يدخل حساب الـRP للمتعادلين في الآخر؟ | RD18 بيشترط Koz وحيد | توصية: Vote Kick يفضل مقفول لو الـKoz مشترك؛ والـRP يعتبرهم نفس المركز الأخير | RD18, `VoteKick.kt:154` |
| Q26 | P1 | الـAuto-Match بيدخل طاولة عملها لاعب. (١) قاعدة الـtier بين الداخل وصاحب الطاولة (RD29: فرق تير واحد؟) (٢) الداخل يشوف إعدادات الطاولة قبل ما يدخل؟ (٣) لو مفيش طاولة منتظرة: رسالة ولا ينتظر ولا ينشئ طاولة؟ | الكود بيبحث بنوع الماتش بس | توصية: (١) نفس RD29 بين الداخل وصاحب الطاولة (٢) أيوه يشوف المدة والبوت قبل الدخول (٣) رسالة "مفيش طاولات دلوقتي" مع زرار إنشاء | `MatchmakingRequest.kt`, RD9/RD29 |
| Q14b | P1 | ROOM بقى يحسب 2 Koz: في أي حالات؟ (خروج / إزالة ١٥ تايم آوت / استبدال بعد قطع اتصال)؟ وعدّاد الـ٧ استلامات موجود في الـROOM ولا في الـRanked بس؟ | A5.1 كانت بتعفي ROOM | توصية: الخروج والإزالة بالتايم آوت فقط؛ الاستبدال بعد قطع الاتصال مفيهوش عقوبة؛ عدّاد الـ٧ Ranked بس | A5.1 |
| Q1 | P2 | General Pass في Normal بيضرب ×2: لو في Sa'ayda متراكم (×4)، النتيجة ×8 ولا ×2 ولا ×4؟ | القواعد ما حددتش | توصية: يضاف على المضاعف الحالي بحد أقصى ×8 | `Bidding.kt:493`, QuickMatch:164 |
| Q3 | P2 | تمديد الـSuper Call في الأدوار السريعة: بيحصل بس لو المتصل نجح (الكود) ولا دايماً (النص)؟ | مثال: متصل فشل بـSuper في الدور 15 → الكود مفيش دور زيادة، النص دور زيادة | توصية: نثبت الكود (لازم ينجح) ونعدل النص | `RoundScore.kt:234` |
| Q4 | P2 | Normal Dash + Risk في Classic (جدوله ثابت) يتصرف إزاي؟ | قرارك اتحدد لـNormal بس | توصية: نفس مبدأ Classic المكتوب (Risk=…) نراجعه معاك بمثال | `CANONICAL_RULES.md:192` |
| Q16 | P2 | في ROOM (٢–٣ لاعبين): King/Second/Third/Koz يتحسبوا إزاي؟ | التعريف مفترض ٤ لاعبين | توصية: المركز الأول فقط King وآخر مركز Koz لو في ٣+؛ ماتش لاعبين = بدون Koz | RD17 |
| Q17 | P2 | عدد الماتشات (Games Played) والنسب هتتجمع برضه بين ROOM وRANKED؟ | القرار ذكر الأربع عدادات بس | توصية: أيوه، الكل مجمّع | RD23 |
| Q18 | P2 | نحتفظ بسجل النتيجة بس ولا بأي سجل تفصيلي للنزاعات/الأمان لفترة؟ | الحذف بيكلف writes ومفيش استرجاع | توصية: النتيجة + RP ledger + مفتاح التكرار فقط؛ سجل الحركات يتمسح بعد تأكيد الحفظ | decision record §1.L |
| Q6 | P2 | حذف الحساب مؤجل: ممكن Google Play يرفض الإطلاق بدونه. تقبل المخاطرة أو نعمل صفحة ويب + حذف بسيط؟ | سياسة المتجر | توصية: نتحقق من السياسة الحالية قبل الإطلاق (TECH T9) ونجهز رابط ويب بسيط | `docs/release/play-listing.md:99` |
| Q19 | P2 | قواعد غير مثبتة: حد أدنى لـWith، قايمة With بعد Super في الأدوار السريعة، نص A1 (bid²). تثبت الكود ولا النص؟ | عدم تطابق وثايق/كود | توصية: نثبت الكود ونعدل الوثايق | audit §5 |
| Q20 | P2 | الإنترنت المقطوع/السيرفر واقف: المؤقتات تتجمد وتتعاد بعد الرجوع؟ | بيأثر على العدالة | توصية: أيوه، من غير أي كتابة إضافية | decision record |
| Q21 | P2 | الـRanked يتطلب إيميل متحقق منه؟ و App Check / Play Integrity نشغله؟ | منع الحسابات المتعددة | توصية: إيميل متحقق مطلوب؛ App Check مرحلة لاحقة (مجاني، 10k/يوم افتراضي) | AuthRepository, Play Integrity docs |
| Q22 | P2 | الليدربورد مؤجل، فهل الـseasons وإعادة الضبط (خطوتين) تفضل في V1؟ ومدة الموسم "٣ شهور" ولا ٩٠ يوم؟ | RP مرتبط بالموسم | توصية: نبقي الـreset الكسول بدون سجلات للاعبين غير النشطين، ومدة ثابتة تتحدد | `SeasonResetRun.kt`, RD24 |
| Q24 | P2 | UNRANKED وROOM يتنقلوا بعدين لنفس سيرفر الـauthority؟ | ROOM محتاجه من V1 للإحصائيات | توصية: UNRANKED بعد Stage 7، ROOM مع Q15 | A3.5 |

## B. Resolved / decided / deferred (no question needed)

| Topic | Status | Basis |
|---|---|---|
| Timeout grace window | DECIDED: none (zero extra read/write ordering) | replanning brief |
| Season attribution across a boundary | DECIDED: completion season | replanning brief |
| Ranked anonymous policy | DECIDED: no Ranked for guests; linked = registered | D |
| Disconnect vote in Ranked | RESOLVED: not held (A5.2); configured bot takes the seat (roadmap line 2056) | records |
| 2 Koz / vote kick 1 Koz / OPEN-4, OPEN-5 | RESOLVED by A5 (except Q14 stats interaction) | A5.1 |
| Match completion off-by-one | RESOLVED: all checks equivalent | engine code |
| Voice signaling | DECIDED by delegation: match connection when voice is built (voice is ROOM-only, post-V1) | brief |
| Leaderboard, inactive-player board visibility | DEFERRED (K) | K |
| Inactive players need season-board records | Not needed in V1 (no board) | K |

## C. Technical experiments (Claude performs; not owner decisions)
| ID | Experiment | Measure |
|---|---|---|
| T1 | Engine ES module in workerd + ≥200-seed JVM/Node/workerd parity | zero diffs; bundle start-up under limit |
| T2 | Delete cost: row deletes vs `deleteAll()`/table drop on DO SQLite | rows written per match with and without cleanup |
| T3 | Quota accounting: can usage be read in-code at 90%? | counter error vs dashboard |
| T4 | Backup lag/outbox: stale backup cannot award RP twice | fault-injection suite |
| T5 | D1 batch atomicity, trigger abort, 50-query cap | local then approved cloud |
| T6 | Durable recovery: kill mid-trick and between result commit and D1 | replay equality, one ledger row |
| T7 | Anonymous→registered link keeps uid; `sign_in_provider` claim value | Firebase Auth emulator |
| T8 | Hibernation/duration billing for a shared host | GB-s per match |
| T9 | Current Play account-deletion policy | policy citation |

## D. Answered (recorded in the 2026-10-21 decision record; do not ask again)
| ID | Answer |
|---|---|
| Q2 | Sa'ayda: count rounds 14–18 (+1 each), then final-round winner check (detail and engine gap in record §1.B; follow-ups in Q2b) |
| Q7 | Ranked game creator chooses bot difficulty and personality (public-table gap: Q7b) |
| Q8 | 2 min from first player entering; ready = connected and seated |
| Q9 | Humans get normal RP; opponent strength from humans only |
| Q10 | Counter increments only when a bot takes over after a disconnection; not for the 50 s inactivity timeout (detection detail: Q10b) |
| Q11 | 7th qualifying takeover: removed from the current match, 2 Koz, not an account ban |
| Q13 | At 90% the backup hosts new matches only; running matches stay; results wait for one central RP database (provider/sync = engineering) |
| Q15 | ROOM stays app-managed; server records the final result only (integrity policy: Q15b) |
| Q15b | ROOM result recorded only when all four players' apps report matching results |
| Q7b | Ranked entry has two paths: create the match yourself, or Auto-Match (Auto-Match bot settings: Q7c) |
| Q2b | MINI uses the same logic on 6–10; Super Call extensions combine; tied final round = has a winner (follow-ups Q2c, Q2d) |
| Q10b | Server detects disconnection; waits the decision timer, then 10 more seconds; then the bot takes over (details: Q10c) |
| Q2c | Extension caps removed; the final-round winner rule repeats until a winner |
| Q2d | Koz = every player tied at the lowest score unless all four tie (all King); shared Koz players are Koz, not Second/Third (follow-up Q2e) |
| Q7c | Auto-Match joins a player-created table; bots are the creator's choice (follow-up Q26) |
| Q10c | After timer: MEDIUM plays, timeout counter +1; after 10 more disconnected seconds the creator's bot takes over, takeover counter +1; 10 s fixed |
| Q14 | 2-Koz departures count in RANKED and ROOM; UNRANKED excluded (follow-up Q14b) |
| Q5 | Build Google sign-in and email sign-up in Android V1 via Firebase Auth, no Play Games |
| Q12 | Approved to write the ADR after Stage 0 succeeds (ADR text not yet approved) |
