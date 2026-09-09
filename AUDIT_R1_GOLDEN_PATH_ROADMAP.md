# Audit شامل: R1 Dealer-Gate + Golden Path + roundArchive — الحالة الفعلية وخطة الطريق

**التاريخ:** 2026-09-08 · **الفرع:** `claude/r1-verification-ci-audit-t2a0xz` @ `e31ebbb` · **الأساس:** قراءة كود فعلية، بدون أي تعديل/push
**نقطة البداية (معطاة، لم يُعَد التحقق منها مستقلًا):** PR #8 مفتوح غير مدموج (6 commits ‏`8ca8e74..e31ebbb`)، آخر run حقيقي emulator (run #9 ‏@ `e31ebbb`): ‏1138 passed / 0 failed، الماتش كمّل ديل→rematch وK.1-K.4 وL.1-L.3 كلها PASS. أي سطر أدناه يقول "per brief" يعني معتمد على هذه المعطيات لا على log أعدت فتحه بنفسي.

**ملاحظة تناقض موثق (يحتاج قرار، لم أحله):** رسالة commit ‏`e31ebbb` نفسها تقول حرفيًا "Not yet re-verified against a live emulator run … recommend re-running the R1 Golden Verification workflow" — بينما معطيات المهمة تقول run #9 على نفس الـSHA نجح 1138/0. الاثنان لا يمكن أن يكونا صحيحين معًا لنفس الـSHA بنفس المعنى. المرجح: الرسالة كُتبت قبل الـrun، والـrun جاء بعدها. **المطلوب:** تثبيت رقم الـrun الـURL والـevidence artifact الذي يثبت 1138/0 على `e31ebbb` قبل الدمج.

---

## 1. ملخص تنفيذي (Evidence-backed)

| # | الحقيقة | الدليل |
|---|---|---|
| 1 | تنسيق `GP-01…GP-14` **غير موجود في الريبو أصلًا** — البحث الشامل يعيد صفر نتيجة | `grep GP-[0-9]` → No files found (2026-09-08). لا يوجد ملف gate-by-gate قديم يُبنى عليه |
| 2 | الـgates الفعلية الوحيدة هي labels داخل `scripts/golden-path.mjs`: ‏`0.0–4.5` + per-trick + per-round + `K.1–K.4` + `L.1–L.3/L.3b` | `scripts/golden-path.mjs:510,525,545,585,588,607,609,613,633,642,649,660,672,675,722,761,779,790,793,816,828,839,858,874` |
| 3 | PR #8 يغيّر schema فعليًا (breaking): parent `cardLog/biddingLog` يُصفَّران عند كل advance/completion، والتاريخ ينتقل إلى `roundArchive/{round}` | `design-ui/match-service.js:1486-1527,1553-1666,1870-1889` + `firestore.rules:1011-1037,1202-1213,1539-1604` |
| 4 | لا يوجد أي consumer إنتاجي لـ`roundArchive` — نظري/للتشريح فقط اليوم | `grep roundArchive` → الكتّاب فقط `match-service.js:1615,1871`؛ تعليق الكود نفسه: "TODAY nothing reads it" (`match-service.js:1523-1526`، `firestore.rules:1560-1562`) |
| 5 | ‏`test.yml` لا يغطي هذا الـPR ولا يغطي Golden Path أصلًا | `test.yml:17-20` الفروع فقط `claude/busy-bohr-ez5rz3` + `main` (فرع PR الحالي غير مشمول)؛ الـrunner يجمع `tests/*.cjs|*.test.js` فقط (`scripts/run-tests.mjs:44-46`) بينما الـgolden في `scripts/`؛ و`r1-golden-verification.yml:11` يدوي `workflow_dispatch` فقط |
| 6 | دمج PR #8 **سيكسر** `deploy-production.yml` حتمًا (SHA guard) | المتوقع `3ceb49…f557b` (`deploy-production.yml:29`) ≠ الحالي `EAB4F9FA…031679` (مقاس بـ`Get-FileHash firestore.rules`) |
| 7 | تصنيف "ranked-only MVP limitation" ما زال صحيحًا بالكامل (cardLog prefix، الـscore، الـhand، الـturn) — لم يغلقه PR #8 | `firestore.rules:669-695,778-788,872-902,960-973` + `SecurityArchitecture.md:57-60,68,74-76,86-87` + `match-service.js:1795-1808,1859` |
| 8 | كسر invariant موثق: كل الوثائق ما زالت تقول `cardLog` "append-only NEVER cleared" بينما الكود الجديد يُصفره كل round | `match-adapter.js:129,261,1503,1774` + `match-service.js:469-487` + `Sprint_RoundLifecycle_Architecture_Report.md:30` مقابل السلوك الجديد `match-service.js:1653-1654,1886-1887` |
| 9 | ملفات untracked عددها 14 مسارًا (10 في `git status` + صور/evidence) — منها ما يستحق PR منفصل ومنها ما يجب حذفه | `git status --porcelain` + جدول §5 أدناه |

---

## 2. جدول الـGates (الحالة المحدثة — الأساس: labels الكود الفعلية، لا تنسيق مخترع)

> لا يوجد `GP-01…GP-14` في الريبو (مثبت أعلاه)، فالجدول أدناه يستخدم labels الهarness نفسها. "PASS مؤكد" = label موجود في الكود + المعطاة run #9 تقول PASS؛ لم أُعد تشغيل emulator محليًا في هذا الـaudit (ممنوع التعديل، ولا CI logs فُتحت)، فأي gate يعتمد على سلوك زمني أضع بجانبه ما يقيده من الكود.

### Phase 0-3: Auth / Room / Match-start / Seats / Deal

| Gate (label الكود) | المعنى | الحالة | الدليل |
|---|---|---|---|
| `0.0` All 4 SDKs loaded | 4 صفحات حقيقية تحمل firebase compat | PASS مؤكد (per brief) | `golden-path.mjs:501` |
| `0.1` 4 distinct Auth uids | signup حقيقي emulator | PASS مؤكد (per brief) | `golden-path.mjs:510` |
| `1.1` P1 creates room | `RoomService.createRoom` | PASS مؤكد (per brief) | `golden-path.mjs:518` |
| `1.2` identical membership | 4 عملاء يرون نفس `players[4]` | PASS مؤكد (per brief) | `golden-path.mjs:525-527` |
| `2.1` match started via all-ready trigger | `setReady` ×4 → `maybeStartMatch` الحقيقي، والـcreator أخيرًا عمدًا | PASS مؤكد (per brief) | `golden-path.mjs:537-545` + التعليق عن creator-only guard |
| `2.2` correct seat p1..p4 | لا cross-assignment عبر `MatchScreenDebug.getLocalSeatId` | PASS مؤكد (per brief) | `golden-path.mjs:585-587` |
| `2.3` agree same matchId | نفس الـmatchId عند الأربعة | PASS مؤكد (per brief) | `golden-path.mjs:588-589` |
| `3.1` hand-authority firestore | `getHandAuthorityMode()==="firestore"` (سلك Sprint F) | PASS مؤكد (per brief) | `golden-path.mjs:607-608` |
| `3.2` 13-card authoritative hand | كل عميل 13 ورقة | PASS مؤكد (per brief) | `golden-path.mjs:609-610` + STOP gate عند الفشل `:615-622` |
| `3.3` 52 unique, no leak | shuffle واحد حقيقي بلا تسريب | PASS مؤكد (per brief run #9) — **مع سابقة FAIL موثقة**: production run ‏2026-08-26 سقط هنا `unique=41/52` | `golden-path.mjs:613` + `golden-prod-evidence/findings.json:3` + `evidence.jsonl` سطر STOP |
| `3.4` P1 cannot read P2 hand | رفض rules حقيقي (محاولة `Db.collection(hands/p2).get()` مباشرة) | PASS مؤكد (per brief) | `golden-path.mjs:633-634` |

**R1 dealer-gate (خارج labels أعلاه لكنه صلب المهمة):** الـproduction incident أثبت أن أي عميل كان يحاول `dealRound` عبر `maybeDealRound()` غير المقيد (`match-adapter.js:2077-2086` وقتها، والتشريح `docs/postmortem/2026-08-26-deal-denial.md:48 V-FINAL + docs/reviews/2026-08-26-bundle-forensics.md:386-440`). الـR1 verification المقصود في PR #8 هو إثبات أن الـdeal الحالي (dealer-gate + idempotent `ALREADY_DEALT`) يصمد في golden emulator. **غير محقق مستقلًا هنا:** لم أجد label باسم R1/dealer داخل `golden-path.mjs` نفسه — التغطية ضمنية عبر `3.1-3.3` لا عبر gate مسمى. يُسجل كفجوة تسمية، لا فجوة سلوك (P1 أدناه).

### Phase 4-5: Bidding + Tricks + Rounds (Round 1 ثم 2..cap)

| Gate | الحالة | الدليل والقيود |
|---|---|---|
| `4.1` Round 1 bidding عبر المسار الحقيقي | PASS مؤكد (per brief) | `golden-path.mjs:642` عبر `driveBidding` (`:275-307`) الذي لا يقبل إلا `BiddingEngine.canSubmit().legal` (`:244-245`) — يغطي Dash/Auction/Confirm/Estimates بما فيها Forbidden-13 والـCall Cap **ضمنيًا** (عبر الأوراكل، لا assertions مسماة لكل قاعدة) |
| `4.2` Round 1 ‏13 tricks عبر المسار الحقيقي | PASS مؤكد (per brief) | `golden-path.mjs:649` عبر `driveRoundCardPlay` (`:338-447`) الذي لا يقبل إلا `TableEngine.canPlayCard().legal` (`:320-321`) — يغطي follow-suit **ضمنيًا** لا صراحة |
| `4.3` converge Round 2 | PASS مؤكد (per brief) | `golden-path.mjs:660-661` |
| `4.4/4.5` Round-2 hands حقيقية + 52 unique | PASS مؤكد (per brief) | `golden-path.mjs:672-676` |
| Per-trick `Round R trick T: next turn owner is correct` | PASS مؤكد (per brief) — **باستثناء trick 13 حيث تغيّرت الدلالة في `e31ebbb`** | `golden-path.mjs:433-434`؛ لـtrick 13 يُتجاوز الفحص (`completedTrick>=13 \|\| …` `:428`) ويُقبل الـadvance كتسوية (`:140-158` + `TRICK_SETTLEMENT_VIA_ADVANCE :156`) لأن `advanceToNextRound` تحقق 52/52 قبل الـreset (`match-service.js:1599-1604`) |
| Per-trick `Firestore/local agrees` | PASS مؤكد (per brief) | `golden-path.mjs:435-436` (`cardPhase ∈ {PLAY,RESOLVING}` + نفس طول الـlog + engine `PLAY/DONE` بلا plays عالقة) |
| `Round NN — 13 tricks` + `Round NN→NN+1 convergence (live maxRounds=…)` | PASS مؤكد (per brief) حتى النهاية | الحلقة `rn=2..30` (`:705`) مع سقف 30 لا 18 (`8ca8e74`؛ `ROUND_LOOP_SAFETY_CAP=30` `:701`) وقراءة `maxRounds` الحي كل boundary (`:737`) — يغطي extensions 14-18 (`extendMatchRounds`) |
| `ROUND_WINDOW_BASELINE` + `TRICK_SETTLEMENT_DIAGNOSTIC` + `ACTOR_PAGE_NOT_CONVERGED` | Log-only تشخيصي (أُضيف في `13dd7bd`) — لا يغير verdict | `golden-path.mjs:347-353,161-177,378-395`؛ رسالة `13dd7bd` تنص "log-only, no behavior change" |

### Phase 6-7: Completion + Rematch (K/L)

| Gate | الحالة | الدليل |
|---|---|---|
| `K.1` status complete authoritative | PASS مؤكد (per brief) | `golden-path.mjs:779-780` (`loadMatch().status==="complete"`) |
| `K.2` نفس final scores عند الأربعة | PASS مؤكد (per brief) — **consistency فقط، لا صحة** (الـscores تُقارن ببعضها `JSON.stringify`، لا يُعاد اشتقاقها) | `golden-path.mjs:790-792`؛ القيد الجوهري `match-service.js:1795-1808` + `firestore.rules:1221-1224` (القاعدة تتحقق من winner==max فقط) |
| `K.3` نفس winner(s) | PASS مؤكد (per brief) — نفس قيد K.2 | `golden-path.mjs:793-795` |
| `K.4` رفض كتابة بعد الاكتمال | PASS مؤكد (per brief) — **تغطية جزئية**: محاولة `submitCard` واحدة من p1 فقط، وتعتمد على رفض client-side (`phase!=="PLAY"`) قبل الـrules | `golden-path.mjs:798-817` (التعليق `:799-805` يصرح بذلك) |
| `L.1` createRematchVote حقيقي | PASS مؤكد (per brief) | `golden-path.mjs:828` |
| `L.2` أربعة YES حقيقية | PASS مؤكد (per brief) | `golden-path.mjs:839` |
| `L.3` new matchId عبر الـwatcher الحقيقي (لا استدعاء يدوي لـ`createRematchMatch`) | PASS مؤكد (per brief) | `golden-path.mjs:858-859` عبر `maybeAdvanceRematchVote` + `GameState.match.id` الجديد (`:851-855`) |
| `L.3b` fallback: vote doc `NEW_MATCH_CREATED` + `newMatchId` | غير محقق في run #9 (fallback لا يُستدعى إلا عند فشل L.3) — موجود كوديًا ومختبر؟ **غير محقق** | `golden-path.mjs:874-875`؛ لا دليل run يثبت تنفيذه |

**Gates سلبية صريحة (تُحسب PASS بعدم الوصول):** إن لم تكتمل المباراة يُطبع "Phases 6/7 NOT REACHED, do not fake unreached phases" (`:879`) — سليم منهجيًا، لكنه يعني أي run متوقف مبكرًا يُخفي K/L كليًا بدل أن يفشلها.

---

## 3. فجوات التغطية خارج الـGolden Path نفسه

### 3.1 ‏cardLog legality (follow-suit / forbidden-13 / …) — التصنيف القديم ما زال صحيحًا
- **Client-side مغلق، server-side مفتوح عمدًا:** ‏`submitCard` تستدعي `assertLocalTurn` قبل أي كتابة (`match-service.js:953`؛ التوثيق `:808`) و`TableEngine.canPlayCard()` قبل فتح الـtransaction (`:1086`؛ التوثيق `:815-819`)، و`submitBiddingAction` تستدعي `BiddingEngine.canSubmit()` (`:1379`) مع `STALE_GAME_STATE` عند تغير الـversion (`:1395`). هذا حقيقي ومقروء.
- **لكن الـrules لا تتحقق من الليجاليتي ولا يمكنها:** ‏`isValidCardSubmission` تتحقق من الشكل العام فقط (`isValidCardShape` ‏`firestore.rules:660-667`) + المالك + `version+1` + نمو الـlog بواحدة (`:696-772`)، مع قيد صريح أنها لا تعيد التحقق من الإدخالات السابقة (`:669-695`) ولا أن الـturn الجديد هو **الصحيح** (`:778-788`). نفس الشيء للـbidding (`:814-945`: لا turn-ownership أصلًا `:884-902` + قيد prefix `:872-882`).
- **الخلاصة:** تصنيف "client-authoritative, MVP-only — not suitable for ranked/competitive" (`SecurityArchitecture.md:57-60`، وأصله `CardAuthorityHotfix_4.2.1.md:48`) **ما زال دقيقًا اليوم**، وPR #8 لم يدّع إغلاقه. الـgolden bots تختار أول مرشح يقبله الأوراكل الحقيقي (`golden-path.mjs:244-245,320-321`)، وهذا يثبت "اللعبة الشريفة تكمل" لا "الغشاش لا يستطيع الكتابة".

### 3.2 ‏score/hand content verification — ما زالت ranked-only limitations
- **الـscore:** ‏`endMatch` يتحقق من الاتساق الداخلي فقط (`winnerIdsMatchFinalScores` ‏`match-service.js:1859`)، والتوثيق يصرح أن wrong-but-consistent scores لا يكتشفها شيء (`:1800-1808`)، والـrule تطابق winner==max فقط (`firestore.rules:1188-1225`). الـK.2/K.3 يقارنان العملاء ببعضهم، لا بالحقيقة.
- **الـhand:** قاعدة الـdeal تتحقق من الشكل والـround والـpairing (`SecurityArchitecture.md:72-73`) لكن **محتوى الورق عمدًا غير قابل للتحقق** (نفس المرجع `:74-76`): أي عضو جالس يستطيع نظريًا اختيار أيادٍ صالحة الشكل لنفسه وللخصوم. الـgolden `3.3` يثبت shuffle شريف واحد، لا استحالة الغش.
- **الـturn بعد الـRESOLVING:** قيد `oldData.turn==uid` غير فعّال فعليًا لأول ورقة بعد كل trick بعد الأول (`turn==null`) — موثق بصراحة (`SecurityArchitecture.md:68`؛ أصله `TrickResolutionSync_4.3.md:63`). ما زال قائمًا.

### 3.3 ‏roundArchive: نظري بحت اليوم — هل يُبنى له consumer؟
- **لا يوجد:** البحث الكامل (`grep roundArchive`) يعيد الكتّاب فقط (`match-service.js:1615,1871` + القواعد `1539-1604`) وصفر قرّاء إنتاجيين. الكود نفسه يشهد: "TODAY nothing reads it … forensics + future review screen" (`match-service.js:1523-1526`؛ `firestore.rules:1557-1562`). لا توجد شاشة "review rounds" ولا route ولا UI (`grep "review rounds"` → فقط هذان التعليقان).
- **التوصية: يُؤجَّل أي consumer.** الـarchive اليوم pressure-valve لتفادي 500s (مثبت بسببين CI في رسالة `7d9895d`)، لا ميزة منتج. بناء شاشة مراجعة الآن يضيف سطح قراءة/خصوصية (أرشيف كل الجولات مرئي لأي لاعب — القاعدة `allow get` لأي seated ‏`firestore.rules:1599`) بلا طلب منتج. **غير ذلك:** إن أُريد لاحقًا، يُبنى read-only + pagination + يُحسم هل الخصوم يرون أوراق الجولات القديمة (قرار منتج/خصوصية، ليس تقنيًا فقط).

### 3.4 الـworkflows: فجوة CI حقيقية (تستحق خطة، ليست تجميلية)
1. **`test.yml` لا يعمل على هذا الـPR أصلًا:** ‏`on.push.branches` = `claude/busy-bohr-ez5rz3` + `main` فقط (`test.yml:17-20`). فرع PR #8 (`claude/r1-verification-ci-audit-t2a0xz`) خارج القائمة — أي push هنا لا يشغّل CI إطلاقًا.
2. **`test.yml` حتى لو عمل، لا يشغّل Golden Path:** الـrunner يجمع `tests/*.cjs|*.test.js` فقط (`run-tests.mjs:44-46`)، والـgolden يسكن `scripts/golden-path.mjs` — غير مكتشف. لا يوجد step يستدعيه في `test.yml:47-54`.
3. **الـR1 workflow يدوي فقط:** ‏`r1-golden-verification.yml:11` = `workflow_dispatch` بلا `push/pull_request` — لا حماية merge، والاعتماد على تشغيل يدوي وتذكّر.
4. **النتيجة:** وصف PR #8 ("بعيدة عن تغطية Golden Path") **صحيح ومؤكد كوديًا**. الـ1138/0 المذكورة جاءت من تشغيل يدوي/محلي (run #9)، لا من gate آلي يحرس الـmerge.

---

## 4. تقييم المخاطر المتبقية

### R-A. الـhonest limitation في `roundArchive` (advance بلا archive) — `firestore.rules:1570-1578`
- **النص:** الاتجاه العكسي غير مفروض — عميل يستطيع الـadvance بسجلات فارغة متجاوزًا الأرشفة (فقدان تاريخ، اللعب غير متأثر؛ السبب: `getAfter()` ديناميكي من int غير قابل للتعبير في الـsubset الآمن).
- **الاحتمالية:** متوسطة-منخفضة — تتطلب كتابة SDK خام متعمدة من عضو جالس (الـUI/adapter الطبيعي يؤرشف دائمًا `match-service.js:1657,1879`)؛ ليست خطأ عفويًا.
- **الأثر:** منخفض على اللعب (المحركات تسجّل محليًا كل round؛ الإكمال يقرأ `winnerIds/finalScores` فقط) — مرتفع على **التدقيق/المنازعات** (الجولة المفقودة لا يمكن إعادة اشتقاق trick-winners منها لاحقًا) وعلى أي شاشة مراجعة مستقبلية ستعرض فجوات.
- **الحكم:** تُقبل للـMVP amigo-friends، **تُوثق كـranked-blocker** مع بقية الـtrust boundary. لا تستحق Cloud Function اليوم (نفس مبرر Spark في `SecurityArchitecture.md:75`).

### R-B. سباقات أخرى من رسائل الـcommits — هل تعيش على production أم emulator فقط؟
| السباق | emulator-only أم production أيضًا؟ | الدليل والحكم |
|---|---|---|
| ‏STALE_GAME_STATE حول Round 9 (تحقيق مقفول) | emulator-only — **يبقى مقفولًا**، لا fix إنتاجي | `INVESTIGATION_CLOSEOUT.md:9-17` (تحقق real-Firestore ‏0ms convergence ‏3/3). الـretry في الـharness (`golden-path.mjs:401-406`) harness-only، متسق مع الإغلاق لا مناقض له |
| ‏Round-16 rules race (4 عملاء `extendMatchRounds`) | emulator-only — **يبقى مقفولًا** | `INVESTIGATION_CLOSEOUT.md:21-29` (real-Firestore ‏12/12 بلا denial). PR #8 لم يمس `extendMatchRounds` — متسق |
| ‏archive+advance contention (كل round boundary، 4 عملاء) | **production أيضًا — حقيقي**، والـfix في PR #8 fix إنتاجي لا emulator-only | رسالة `4a09081` تثبت denials مُعاد إنتاجها 3/3 محليًا + winner واحد + retry-guard (`match-adapter.js:1869-1888` بشكل `{round,version}`)؛ التصميم "any client may attempt" (`match-service.js:1450-1454`) يضمن contention كل جولة على أي backend. الـretry المحدود يمنع الـdeadlock لكنه **لا يزيل النافذة** — كل boundary ما زال سباق 4 أطراف |
| ‏trick-13 settlement race (advance يسبق poll الـsettlement) | **production أيضًا** (نافذة زمنية، ليست emulator quirk) | `e31ebbb` + `golden-path.mjs:140-158`: الـparent يقفز 51→0 عند فوز الـadvance. القبول الجديد مبرر (الـadvance تحقق 52/52 مسبقًا `:1599-1604`) لكنه **يُضعف استقلالية الـassertion** (نفس الكاتب يشهد لنفسه) — مقبول للـharness، لا يُسوَّق كتحقق مضاعف |
| ‏double-deal race (أصل incident الإنتاج) | production حقيقي ومثبت — **الحل الحالي تخفيف لا إزالة** | `docs/postmortem/2026-08-26-deal-denial.md` V-FINAL (P1 فاز، P4 خسر Correct-Denial ~95%). الـP0-1 المقترح (dealer-gate لـ`maybeDealRound` `:48`) **لم يُنفذ** في PR #8 — النافذة الهيكلية قائمة، والاعتماد على idempotency + `ALREADY_DEALT` |

### R-C. ما الذي يحتاج نفس صرامة Golden Path ولم يُغطَّ بعد؟
- **Reconnect mid-match:** ‏`verify-sprint-c-reconnect.cjs:1-30` موجود لكنه **مهجور عمليًا** — مسارات مكتوبة يدويًا (`ROOT=/home/user/demo-test`، `PROJECT_ID=demo-test-sprintc`، `CDN=/tmp/fb-cdn-cache`) لا تعمل على checkout نظيف، وخارج الـCI تمامًا، ويتعمد single-client deal "لتجنب الـemulator behavior غير المحلول". الـadapter يدّعي rebasing للـcount registries عند shrink النافذة (`match-service.js:1516-1519`) بلا إثبات E2E محدّث بعد الـwindow change. **غير مغطى بنفس الصرامة.**
- **Rematch:** مغطى E2E في الـgolden (L.1-L.3) عند اكتمال الماتش — **كافٍ**، لكن `L.3b` fallback بلا دليل تنفيذ، و`verify-rematch-vote*.cjs` (two-client Playwright) خارج الـCI.
- **Presence/abandonment:** ‏`design-ui/presence-service.js` ملف stub كامل (`notImplemented` لكل الدوال) — الخصم المختفي غير مرئي. لا golden gate يلمسه. **غير مغطى إطلاقًا.**
- **Post-completion trust:** ‏K.4 محاولة واحدة client-side فقط؛ لا اختبار rules-level (محاولة كتابة خام بعد `complete` عبر REST) ولا اختبار score-forgery (finalScores متسقة لكن خاطئة تُقبل — بالتصميم اليوم).

---

## 5. الملفات الـuntracked (14 مسارًا) — ما يدخل PR منفصل وما يُحذف

المصدر: `git status --porcelain` (10 مدخلات `??`) + محتويات `golden-prod-evidence/` المقروءة.

| الملف | الحجم | الحكم | السبب |
|---|---|---|---|
| `docs/postmortem/2026-08-26-deal-denial.md` | ~27K | **PR منفصل (docs-only)** — مهم | V-FINAL verdict + proposals P0-1..P3-7 (`:44-54`) + ADDENDUMs. هو السجل الوحيد المغلق للـproduction incident. يُدمج بعد إسقاط الأقسام المتجاوَزة أو وسمها superseded بوضوح (الملف يفعل ذلك already `:65-66`) |
| `docs/postmortem/h-matrix-real-backend-runbook.md` | ~2.5K | **مع السابق، نفس الـPR** | runbook تجربة Cell C الحاسمة (`:41-44`). بلا قيمة وحده؛ مع الـpostmortem يكتمل السياق |
| `tests/h-matrix.real-backend.cjs` | ~19K | **مع السابق، نفس الـPR — مع guard** | hard-guard ضد project اللعبة (`GAME_PROJECT` ‏`:39` + `bail` ‏`:56`) وno-op بلا config (`:57-60`). آمن للدمج، مفيد لإعادة قياس precedence لاحقًا |
| `tests/repro-deal-denial.rules-emulator.test.cjs` | ~30K | **PR منفصل (tests)** — مهم بشروط | Matrix H2b/H2c تُثبت emulator ordering (400 vs 403) وتمنع الانزلاق. **شرط:** يُوسم emulator-only بوضوح في الاسم/الترويسة (يفعل: `:1-33`) ويُثبت أنه يعمل تحت `test:ci` لا يُخرج `SKIPPED` (الـrunner يفشل على كلمة SKIPPED ‏`run-tests.mjs:71`) |
| `tests/dealer-derivation.determinism.test.cjs` | ~9K | **مع PR الـtests السابق** | MOCKED determinism (11/11 سابقة في الـpostmortem ‏`:99`) ضد `uidToSeat`. خفيف، بلا emulator، يقفل باب non-deterministic-dealer نهائيًا |
| `scripts/concurrency-probe.mjs` | ~4K | **يُنقل إلى `tests/` أو يُحذف — لا يبقى في `scripts/`** | معزول عمدًا عن الـgolden (`:1-17`) ويستخدم contexts فقط. في `scripts/` لا يكتشفه الـCI؛ كـ`tests/*.cjs` سيُكتشف تلقائيًا. إن لم يُرغب في gate دائم، يُحذف (الفكرة موثقة في الـcloseout) |
| `golden-prod-evidence/evidence.jsonl` | ~16K/28 سطرًا | **PR evidence منفصل أو LFS — يُحفظ، لا يُحذف** | الـraw الوحيد للـincident (START ‏06:14:07 → STOP ‏unique=41). الـpostmortem يستشهد بأسطره (`L18-19` إلخ). text صغير — مقبول git |
| `golden-prod-evidence/findings.json` | 161B | **مع الـevidence السابق** | سطر واحد (`3.3 unique=41`) — هو الـFAIL نفسه. تافه الحجم، عالي القيمة كـfixture |
| `golden-prod-evidence/lobby-p1..p4.png` | 4 صور | **يُحذف من git — يُحفظ خارجيًا** | لقطات lobby عادية بلا معلومة تشريحية (الـincident في الـdeal لا الـlobby). binary في الـhistory بلا مبرر؛ تُرفع كـCI artifact عند الحاجة (`r1-golden-verification.yml:51-58` يفعل ذلك already) |
| `docs/postmortem/live-rules.today.txt` (115K) + `rules.yesterday.txt` (114K) | ~230K | **يُحذف — يُستبدل بـhashes** | نسختان كاملتان من `firestore.rules` للـbyte-diff (R1c/R1b في الـpostmortem `:81-82`). بعد تثبيت النتيجة، الاحتفاظ بـ230K مكررة في الـtree إسراف — يُحفظ الـSHA256 والسطور محل الخلاف فقط داخل الـpostmortem |
| `predicate-evaluation.json` + `round16-rule-audit.json` + `rule-reproducer.js` | (tracked already — ليست untracked) | **خارج نطاق هذا القرار** | مذكورة للاكتمال فقط (`INVESTIGATION_CLOSEOUT.md:47`) — لا تُمس هنا |

**قاعدة عامة للـPRs المنفصلة:** لا تُخلط evidence/docs مع `firestore.rules` + `match-service.js` في PR #8 (breaking schema). كل PR لاحق: ملفاته فقط + `node --check` + `test:ci` محلي.

---

## 6. تناقضات موثقة تحتاج قرار مالك (لا حل من طرفي)

1. **`e31ebbb` "not yet re-verified" مقابل run #9 ‏1138/0 على نفس الـSHA** (§0 أعلاه). **القرار:** تثبيت URL الـrun والـartifact قبل الدمج؛ إن تعذّر، يُعاد تشغيل `r1-golden-verification.yml` يدويًا على `e31ebbb` ويُرفق الـevidence.
2. **"append-only NEVER cleared" في 5+ مواضع مقابل `cardLog: []` الجديد.** المواضع: `match-adapter.js:129,261,1503,1774`، `match-service.js:469-487`، `Sprint_RoundLifecycle_Architecture_Report.md:30`، و`SecurityArchitecture.md:57-60` (الـprefix-integrity discussion يفترض log دائم النمو). **القرار:** هل تُحدَّث كل المواضع إلى "per-round window + archive" (مقترح) أم يُعاد الـinvariant القديم (يعيد الـ500s)؟ لا وسط آمن.
3. **`INVESTIGATION_CLOSEOUT.md:35-41` "لم يُعدَّل شيء" مقابل PR #8 الذي عدّل الملفات نفسها.** ليس تناقضًا حقيقيًا (النطاقان مختلفان: الـcloseout عن STALE/Round-16 فقط)، لكن أي قارئ مستقبلي سيفهمها كذلك. **القرار:** سطر توضيحي في الـcloseout أو وصف PR #8 يحدّد: "this PR does not reopen the two closed tracks; it fixes stored-array-size + advance-guard + trick-13-harness".
4. **`deploy-production.yml:29` الـSHA المجمد مقابل أي تغيير rules.** الـbreaking change الحالي مثال حي: الـguard سيُفشل بعد الدمج حتى يُحدَّث. **القرار:** هل الـSHA يُحدَّث يدويًا كل مرة (الوضع الحالي) أم يُستبدل بـrelease-hash assertion (المقترح P2-6 في الـpostmortem `:52`)؟

---

## 7. خطة الطريق — أولويات مرتبة (P0/P1/P2) بخطوات عملية وتقديرات حجم

> بلا أرقام زمنية (التزامًا بالقيد). الحجم: صغير S / متوسط M / كبير L. كل بند يبدأ بالدليل لا بالافتراض.

### P0 — يمنع الدمج/النشر الصحيح (قبل merge PR #8)

**P0-1. تثبيت دليل run #9 وتسوية تناقض `e31ebbb` — S**
- الخطوات: (1) جلب URL الـworkflow run + الـartifact (`r1-golden-evidence`: `evidence.jsonl` + `findings.json` كما في `r1-golden-verification.yml:51-58`) والتأكد أن `SUMMARY.passed==1138 && failed==0` وأن الـHEAD هو `e31ebbb`؛ (2) إلصاق الرابط والـSHA في وصف PR #8؛ (3) إن تعذّر، تشغيل `workflow_dispatch` على الفرع وإرفاق الجديد. (4) تعديل رسالة الـcommit؟ ممنوع تعديل التاريخ — يُكتفى بتعليق PR.
- الدليل: تناقض §6.1.

**P0-2. تحديث SHA guard في `deploy-production.yml` + قرار نشر القواعد — S**
- الخطوات: (1) بعد الدمج، حساب `Get-FileHash firestore.rules` من `main`؛ (2) تحديث `expected=` (`deploy-production.yml:29`) في PR لاحق صغير؛ (3) النشر نفسه يتم حصرًا عبر Console من المالك (الترويسة `firestore.rules:14-17` تشترط ذلك)؛ (4) التحقق أن live rules == repo (نفس إجراء R1c في الـpostmortem `:81`).
- الدليل: mismatch مثبت §1.6. بدونه أي deploy يفشل عمدًا.

**P0-3. توسيع `test.yml` ليشمل فرع PR + حماية Golden Path من الـmerge الأعمى — S**
- الخطوات: (1) إضافة `claude/r1-verification-ci-audit-t2a0xz` (أو نمط `claude/**`) إلى `test.yml:17-20`؛ (2) **لا** تُدمج الـgolden الطويلة في `test.yml` (تُبقي `test:ci` سريعة) بل تُضاف `pull_request` trigger خفيف على `r1-golden-verification.yml` أو `paths: [firestore.rules, design-ui/**, scripts/golden-path.mjs]`؛ (3) إثبات: push تجريبي يُظهر job أخضر/أحمر حقيقي.
- الدليل: §3.4. **هذا هو "الفجوة CI الحقيقية" المطلوب خطتها.**

### P1 — صحة/أمان/تغطية (بعد الدمج مباشرة)

**P1-1. إصلاح drift الوثائق append-only → per-round window — S**
- الخطوات: (1) تحديث المواضع الخمسة (§6.2) إلى الصياغة الجديدة + حدود النافذة (≤52 + dozens ≈10KB ‏`match-service.js:1497-1500`)؛ (2) تحديث `SecurityArchitecture.md:57-60` لتوضيح أن الـprefix-integrity gap الآن **داخل النافذة فقط** (إعادة كتابة جولة كاملة ما زالت ممكنة نظريًا لكن التاريخ المؤرشف write-once ‏`firestore.rules:1601-1603`)؛ (3) `grep append-only` مراجعة نهائية.
- الدليل: تناقض §6.2.

**P1-2. تسمية R1 dealer-gate صراحة في الـharness — S**
- الخطوات: (1) إضافة `check("R1 …")` بعد `3.3` يتحقق من `gameState.dealtRound==1` + أربعة `hands` docs + رفض قراءة اليد المقابلة (الموجود `3.4` يُعاد تسميته R1.x)؛ (2) لا سلوك جديد — تسمية فقط ليتطابق التقرير مع المهمة.
- الدليل: §2 (لا label باسم R1 اليوم).

**P1-3. إحياء reconnect E2E بعد الـwindow change — M**
- الخطوات: (1) إصلاح مسارات `verify-sprint-c-reconnect.cjs` المكتوبة يدويًا (`ROOT`, `PROJECT_ID`, `CDN` ‏`:22-30`) لتُشتق من الـrepo/env مثل الـgolden (`__REPO_ROOT__`, `EVIDENCE_DIR` ‏`golden-path.mjs:9-25`)؛ (2) سيناريو: عميل يفصل mid-round (بعد trick 6) ويعود mid-round + عميل يعود بعد advance (يختبر rebasing الـregistries ‏`match-service.js:1516-1519`)؛ (3) يُنقل إلى `tests/` أو يُستدعى من workflow يدوي؛ (4) يُوثق PASS/FAIL بنفس `check()` الصارمة.
- الدليل: §4 R-C.

**P1-4. تضييق نافذة advance-contention (أي عميل يتقدم) — M**
- الخطوات: (1) تقييم dealer-only advance (نفس مقترح P0-1 للـdeal ‏`postmortem:48`) مقابل إبقاء any-client + retry الحالي (`match-adapter.js:1869-1888`)؛ (2) أيًا كان القرار، يُوثق في `match-service.js:1450-1454` + اختبار سباق 4-عملاء يثبت exactly-once archive (الموجود `round-lifecycle.test.cjs:311-326` يغطي التسلسل لا السباق الحقيقي)؛ (3) لا Cloud Function (خارج Spark).
- الدليل: R-B (contention حقيقي كل boundary).

**P1-5. دمج الـuntracked عالية القيمة (3 PRs صغيرة) — S لكل PR**
- الخطوات: PR-A ‏docs (postmortem + runbook + h-matrix)؛ PR-B ‏tests (repro + determinism) مع إثبات `test:ci` بلا SKIPPED؛ PR-C ‏evidence (jsonl + findings) + حذف الصور والـrules snapshots (§5). كل PR: ملفاته فقط، `node --check`، `test:ci` محلي.
- الدليل: جدول §5.

### P2 — مقوّيات (تُؤجَّل بلا ضرر)

**P2-1. شاشة "review rounds" من الـarchive — L (يُؤجَّل)**
- السبب: صفر طلب منتج + قرار خصوصية معلق (§3.3). إن طُلبت: read-only، pagination، وتحديد رؤية أوراق الجولات القديمة للخصوم قبل أي سطر كود.

**P2-2. تقوية K.4 واختبارات rules-level سلبية — M (يُؤجَّل بعد P1)**
- الخطوات عند تفعيله: محاولة REST خام بعد `complete` (توقع rules DENY لا client-side فقط)، ومحاولة `finalScores` متسقة-لكن-خاطئة (توقع **القبول اليوم** — توثيق لا إصلاح)، ومحاولة advance بلا archive (توقع **القبول اليوم** — القيد R-A).

**P2-3. أتمتة الـSHA (release-hash assertion) بدل التجميد اليدوي — S (يُؤجَّل)**
- المقترح موجود (`postmortem:52` P2-6). يُنفذ عندما تتكرر كسور الـdeploy، لا الآن.

**P2-4. Presence/abandonment الحقيقي — L (يُؤجَّل صراحة)**
- ‏`presence-service.js` stub كامل. يُبنى heartbeat/timeout فقط عندما يصبح "الخصم اختفى" شكوى منتج، لا استباقًا.

---

## 8. ما لم يتغير (تأكيدات إيجابية — ليست فجوات)

- **STALE_GAME_STATE وRound-16 race يبقيان مغلقين** (`INVESTIGATION_CLOSEOUT.md:5,49-54`) — PR #8 لا يعيد فتحهما (§4 R-B). لا action.
- **Fast-round Caller/With والـGolden Super Call reset** (المذكورة في `PROJECT_STATUS_AND_MASTER_PLAN.md:60,108-110`): تحققت من `bidding-engine.js:184-207,226-232` — الـ`callerId:null, withPlayers:[]` للـfast rounds ما زال موجودًا كوديًا، لكنه **خارج نطاق هذه المهمة** (R1/Golden Path أنهت ماتشات كاملة 1138/0 بهذا السلوك — أي تغيير engine الآن يُبطل الـevidence). يُسجل كبند engine لاحق، لا يُخلط مع PR #8.
- **`src/` legacy scoring divergence** (نفس المرجع `:59,115-116`): خارج النطاق تمامًا — لا يمسه PR #8 ولا الـgolden. لا action هنا.

---

## 9. Definition of Done لهذا الـAudit (التزامًا بقيود المهمة)

- [x] صفر تعديل كود / صفر push (فحص وقراءة فقط؛ `git status` يؤكد الفرع ما زال `claude/r1-verification-ci-audit-t2a0xz`)
- [x] كل ادعاء مربوط بدليل (ملف:سطر أو رقم commit أو قياس محلي)؛ ما تعذّر إثباته وُسم **"غير محقق"** (L.3b، تفاصيل run #9 الداخلية)
- [x] التناقضات عُرضت كقرارات (§6) لا حُلّت من طرف واحد
- [x] استُخدمت labels الهarness الفعلية أساسًا بعد إثبات غياب تنسيق GP-01…GP-14 (لا تنسيق مخترع)
- [ ] التقرير يُسلَّم كملف markdown بلا commit/push إلا بأمر صريح (هذه الخطوة)

*نهاية التقرير.*
