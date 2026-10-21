# Open-questions register (as of 2026-10-21)

Companion to `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`. Statuses:
**DECIDED** · **RESOLVED-BY-RECORDS/CODE** · **OWNER** (needs the owner) · **TECH** (Claude measures it) ·
**DEFERRED** (out of V1). Nothing here is owner-approved unless it says DECIDED. Questions are in
Egyptian Arabic; priority P1 blocks Stage 1 design, P2 blocks a later phase.

## A. Owner questions

| ID | Pri | السؤال | ليه مهم | الاختيارات / توصية | المصدر |
|---|---|---|---|---|---|
| Q15 | P1 | نتايج الـROOM (King/Second/Third/Koz) تتسجل في البروفايل من V1 — مين اللي يحسبها ويضمن إنها اتسجلت مرة واحدة؟ الـROOM شغال حالياً على Firebase والعميل هو اللي بيحسم النتيجة | من غير سيرفر موثوق أي لاعب يقدر يزوّر نتيجة ROOM وتتجمع مع الـRanked. وCloud Functions مش هتتنشر (من غير Blaze) | (أ) ننقل ROOM على نفس سيرفر Cloudflare (توصيتي، ونحسّها توسعة موافق عليها) · (ب) نقبل نتيجة من العميل للإحصائيات بس ونعترف إنها قابلة للتزوير · (ج) نأجل إحصائيات ROOM لحد ما ROOM يتنقل | `MatchService.kt:749-800`, `firestore.rules:1353-1390`, plan RD11/RD23 |
| Q13 | P1 | الـbackup server هو إيه بالظبط (حساب Cloudflare تاني؟ مزوّد تاني؟)، وبيشيل إيه لما الأساسي يوصل 90%؟ | القرار ده بيحدد هل ممكن يبقى فيه سجلين RP. والشروط الحرة ممكن تمنع حسابين | توصيتي: الـbackup يستضيف ماتشات جديدة بس، والنتايج تستنى في outbox لحد ما يتبعتوا لقاعدة بيانات واحدة (مصدر RP واحد)؛ مفيش failover تلقائي للماتشات الشغالة | decision record §1.I |
| Q2 | P1 | في Sa'ayda: قصدك "الماتش يزيد دور واحد" (زي الكود) ولا "يتمدد من 14 لـ18"؟ | الكتابة بتقول حاجتين مختلفتين. مثال: Sa'ayda في الدور 16 → الكود 19 دور؛ القواعد المكتوبة بتقول الدور الأخير بس؛ دور زيادة ممكن يقلب الفايز | توصية: نثبّت "دور واحد زيادة لأي Sa'ayda في الأدوار السريعة" ونعدل النص المكتوب | `RoundScore.kt:230`, `CANONICAL_RULES.md:21,144,191` |
| Q7 | P1 | الـbot-fill بيقول "البوتات اللي صاحب الغرفة اختارها" — في الـRanked العام (Auto-Match) مفيش صاحب غرفة. مين يحدد البوتات؟ | من غير إجابة التصويت مش مكتمل | (أ) الـRanked العام يستخدم مستوى/شخصية افتراضية (Medium + الافتراضية) · (ب) الـbot-fill للطاولات الخاصة بس. توصية (أ) | plan RD21 |
| Q8 | P1 | "ماتش ستارت" و"اللاعبين الجاهزين" معناهم إيه؟ الدقيقتين بتبدأ من إنشاء الطاولة ولا من أول لاعب ولا من أول نداء؟ وإيه العدد المطلوب بعدين (٤)؟ | بيحدد الـlifecycle كله | توصية: ٤ بشر مطلوبين، العداد من تكوين الطاولة، "جاهز" = متصل وقاعد على الطاولة | decision record §1.F |
| Q9 | P1 | لو التصويت نجح والماتش فيه بوتات: اللاعبين البشر ياخدوا RP؟ والـRP بيتحسب إزاي (قوة الخصم)؟ | الإعدادات الحالية بتفترض ٤ بروفايلات حقيقية | توصية: RP بالقواعد العادية لكن قوة الخصم تتحسب من البشر بس، ومفيش RP للـbot؛ أو مفيش RP لو في بوت (يحتاج قرارك) | `SettlementDb.kt:508,620` |
| Q10 | P1 | عداد الـ٧ رجعات: يزيد مع كل قطع اتصال ولا بس لما البوت يستلم؟ وهل استلام عدم النشاط (٥٠ ثانية) بيتحسب؟ | بيحدد متى اللاعب يتحرم من العودة | توصية: يزيد فقط لما البوت يستلم فعلاً (أي سبب)، والتايم آوت العادي ما يزيدوش | لا عداد في الكود (§1.G) |
| Q11 | P1 | بعد الرجعة السابعة: اللاعب يتشال نهائياً؟ بنتيجة 2 Koz؟ ولا يفضل البوت يلعب مكانه بدون عقوبة؟ | القواعد الحالية بتقول الإزالة الدائمة = 2 Koz في Ranked | توصية: إزالة دائمة + 2 Koz زي إزالة الـ١٥ تايم آوت | `CANONICAL_RULES.md` A5.1 |
| Q14 | P1 | الـUNRANKED مستبعد من الإحصائيات، لكن A5.1 بتحمّل الـ2 Koz على عداد Koz في الـUNRANKED، وبتعفي الـROOM. هل الخروج (leave/إزالة) يتسجل في الإحصائيات المجمّعة للـROOM والـRANKED؟ | تعارض بين قرارين | توصية: يتحسب الخروج (2 Koz) في RANKED وROOM فقط؛ UNRANKED خارج الإحصائيات بالكامل | A5.1, RD23 |
| Q5 | P1 | تسجيل الدخول بجوجل والـsign-up بالإيميل مش موجودين في تطبيق الأندرويد (موجودين في نسخة الويب بس). تبنيهم في V1؟ | الـRanked محتاج حساب مسجّل ومفيش طريقة تسجيل | توصية: نبنيهم عبر Firebase Auth (مجاني) بدون Play Games | `AuthRepository.kt`, `design-ui/login` |
| Q12 | P1 | موافق رسمياً على إلغاء RD11/RD14 بالنص الجديد (ADR) لما Stage 0 ينجح؟ | القرار الحالي "اتجاه" مش نص موافق عليه | توصية: نكتب ADR بعد نجاح Stage 0 | plan RD11/RD14 |
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
