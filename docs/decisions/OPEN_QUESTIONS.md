# Open-questions register (as of 2026-10-21)

Companion to `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`. Statuses:
**DECIDED** · **RESOLVED-BY-RECORDS/CODE** · **OWNER** (needs the owner) · **TECH** (Claude measures it) ·
**DEFERRED** (out of V1). Nothing here is owner-approved unless it says DECIDED. Questions are in
Egyptian Arabic; priority P1 blocks Stage 1 design, P2 blocks a later phase.

## A. Owner questions

| ID | Pri | السؤال | ليه مهم | الاختيارات / توصية | المصدر |
|---|---|---|---|---|---|
| Q15c | P1 | الـUNRANKED دخل الإحصائيات: نتيجته تتسجل بنفس شرط الـROOM (تطابق تقارير تطبيقات اللاعبين الأربعة)؟ | الـUNRANKED شغال على نظام التطبيق زي الـROOM | توصية: أيوه نفس الشرط | decision record §P |
| Q27 | P1 | الماتش اللي الكل سابه: (١) "الكل سابه" يعني إيه ومدة قد إيه (مثلاً الكل خارج المقاعد X دقيقة)؟ (٢) الـRP اللي يتخصم قد إيه (نفس خسارة آخر مركز؟ مبلغ ثابت؟) (٣) لو الماتش وقف بسبب السيرفر/الحصة المجانية، ما يتحسبش على اللاعبين؟ (٤) ROOM/UNRANKED بيتسجل فيهم كوز (١ و٢) بدون RP؟ | بيستثني قاعدة "RP بعد ماتش كامل" | توصية: (١) بعد انتهاء مهلة قصيرة ثابتة من خروج آخر لاعب (٢) نفس خسارة آخر مركز بحسب محرك الـRP (٣) أيوه إعفاء كامل (٤) أيوه | decision record §P |
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
| Q14 | (superseded by Q14b/Q14c) 2-Koz departures; UNRANKED exclusion reversed by Q14c |
| Q5 | Build Google sign-in and email sign-up in Android V1 via Firebase Auth, no Play Games |
| Q12 | Approved to write the ADR after Stage 0 succeeds (ADR text not yet approved) |
| Q2e | No Vote Kick when the Koz is shared (unique Koz required); RP of shared Koz: Q2f |
| Q26 | RD29 tier band joiner↔creator; joiner sees table settings first; no tables → message + Create button |
| Q14b | ROOM leave (any reason) = 1 Koz; RANKED/UNRANKED leave (any reason) = 2 Koz; charged only when the match finishes and the player did not return (follow-ups Q14c, Q14d) |
| Q1 | General Pass consumes the round: round 6 all pass → round 7 ×2; Sa'ayda in 7 → round 8 ×4 (follow-up Q1b) |
| Q3 | Super Call extends only if the Caller succeeds |
| Q14c | UNRANKED enters ALL profile statistics (no RP); reverses decision J's exclusion (follow-up Q15c) |
| Q14d | Passed Vote Kick = 2 Koz; abandoned match (everyone left): all players Koz and all lose RP (details Q27) |
| Q1b | No cap on General Pass/Sa'ayda counts, only the multiplier ×8; Classic General Pass still consumes the round, no multiplier |
| Q2f | Shared-Koz players take the same last placement for RP |
