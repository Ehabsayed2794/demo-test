# Stage 0 — E3: rule/scoring vector parity, JVM ↔ server

**Status: MEASURED 2026-10-29.** This is the exit evidence for the plan's E3 row
(`NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3): "Table-driven vectors T-01…T-16, T-22, T-24 —
every vector identical". E2's corpus is the sibling experiment (`T-23` / V1-C2); this one is the
sharper, rule-by-rule question.

Branch `stage0/e3-vector-parity`, base `dcb954e` (main after PR #188).

## 1. What the experiment is

One vector table, compiled twice — once for the JVM, once as the exact Kotlin/JS production
artifact shape that a worker consumes — and the two rendered outputs compared. The point is not
"the engine is fast"; it is that **every rule the plan names produces the same answer in the
server runtime as in tests and on device**.

- Table: `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/RuleVectorCases.kt` — one entry
  per case, each naming its plan vector (`T-01`…`T-16`, `T-22`, `T-24`).
- Runner: `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/RuleVectors.kt` — the case
  shape, the `MEASURED` / `NOT_IMPLEMENTED` status and the parity verdict logic.
- Export surface: `native/stage0/src/jsMain/kotlin/Stage0Exports.kt` (`runRuleVectors`).
- JVM driver: `native/stage0/src/jvmTest/kotlin/com/estemshan/stage0/RuleVectorTest.kt` — writes
  `build/stage0-parity/jvm-rule-vectors.txt`.
- Cross-runtime check: `native/stage0/src/jvmTest/kotlin/com/estemshan/stage0/RuleVectorParityTest.kt`
  — compares the JVM document with the one workerd produced, and writes the verdict table
  `build/stage0-parity/rule-vector-verdicts.txt` / `.md`.

Each case renders **integers and seat ids only**, one line per case:
`<vector>|<case id>|<status>|<value>|<plan expectation>|<note>`. The document is a text artefact so
that "identical" means *byte-identical*, not "equivalent".

Every case ends in exactly one of three outcomes — never a blank:

| Outcome | Meaning |
| --- | --- |
| **IDENTICAL** | The JVM and workerd rendered the same value, and that value matches the plan. Both values are recorded. |
| **DIFFERENT** | The two runtimes disagreed. The exact diff is recorded. **None occurred.** |
| **NOT IMPLEMENTED** | The engine has no such behaviour. Both runtimes report the same absence, and the reason names the missing behaviour precisely. |

The plan axis is tracked separately: `MATCHES` (the value is what the plan says), `DEVIATES` (both
runtimes agree with each other but the value differs from the plan's wording), `N-A` (no plan
expectation applies because the behaviour does not exist).

## 2. Runtime versions

| Piece | Version |
| --- | --- |
| Base commit | `dcb954e` (main, merge of PR #188) |
| Gradle | 8.14.3 |
| Kotlin | 2.0.21 |
| JDK | Temurin 17.0.20.1 (`D:\Tools\jdk-17`) |
| Kotlin/JS target | `compileProductionExecutableKotlinJs` (plain UMD artifacts) |
| Node | 22.14.0 (Gradle toolchain, `%USERPROFILE%\.gradle\nodejs\node-v22.14.0-win-x64`) |
| wrangler | 4.155.0 (`D:/cache/stage0/node_modules/wrangler/bin/wrangler.js`) |
| workerd | 1.20261023.1, `compatibility_date = "2026-10-29"` |

Artifact hashes at the time of the run (identical copies in the build directory and in the worker
directory):

| File | sha256 | Raw bytes | gzip −9 bytes |
| --- | --- | --- | --- |
| `Estemshan-engine.js` | `d8e6310d235db5850837fac91d4c36c9afb6bceb4ef19496568b7a5bf12f556a` | 296,054 | 45,878 |
| `kotlin-kotlin-stdlib.js` | `77f028b2fda52282699305bc95e07b7345874629502c41d48fe03947369c3f7f` | 195,010 | 31,305 |
| `kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js` | `dd4ef3b5bd828db9606eb1c4dd0b629e134e7935bb1631ba47bd773fb9deac0b` | 572 | 337 |
| `stage0.js` (the probe harness, not the engine) | `a025adcae523702586fc181ca2387737a4d7a6e47e85e435b71e64d8bbe6e0b6` | 134,082 | 26,370 |
| **Total** | — | **625,718** | **103,890** |

## 3. Result — 153 cases, 19 vectors, zero disagreements

| Measure | Value |
| --- | --- |
| Cases in the table | 153 |
| Vectors in scope | 19 (T-01…T-16, T-22, T-24 — every T-ID the plan's E3 row names) |
| **IDENTICAL** (JVM = workerd, and = the plan) | **139** |
| **DIFFERENT** (JVM ≠ workerd) | **0** |
| **NOT IMPLEMENTED** (the engine has no such behaviour) | **14** |
| Plan axis | 136 MATCHES · 3 DEVIATES · 14 N-A |

Byte comparison of the two documents:

```
jvm     native/stage0/build/stage0-parity/jvm-rule-vectors.txt   29,385 bytes
workerd D:/cache/stage0/e3/workerd-rule-vectors.txt              29,385 bytes

sha256 (both)  f52451cf3adef1fc08d78ae9afaeed2c9b7d2728c1bac09e12cb07c5285c4ff3
cmp             exit 0
```

`cmp` reports the two files identical, not merely equivalent. **Zero** diffs, explained or
otherwise.

### 3.1 Every vector, every case

`IDENTICAL` = the two runtimes agree and the value is what the plan says.
`DEVIATES` = the two runtimes agree, but the value differs from the plan's wording (§4).
`NOT IMPLEMENTED` = no such engine behaviour exists on either side (§5).

| Vector | Case | Parity | JVM | workerd | Plan |
|---|---|---|---|---|---|
| T-01 | `t01.s1.pass-cancels-the-round` | IDENTICAL | `GENERAL_PASS,next=2` | `GENERAL_PASS,next=2` | MATCHES |
| T-01 | `t01.s2.next-round-number-and-multiplier` | IDENTICAL | `round=7,mult=2` | `round=7,mult=2` | MATCHES |
| T-01 | `t01.s3.dealer-rotates` | IDENTICAL | `dealer=p2` | `dealer=p2` | MATCHES |
| T-01 | `t01.s4.caller-5-takes-5-at-x2` | IDENTICAL | `delta=50,mult=2` | `delta=50,mult=2` | MATCHES |
| T-01 | `t01.s5.second-pass-x4` | IDENTICAL | `next=4` | `next=4` | MATCHES |
| T-01 | `t01.s6.third-pass-x6` | IDENTICAL | `next=6` | `next=6` | MATCHES |
| T-01 | `t01.s7.fourth-pass-x8` | IDENTICAL | `next=8` | `next=8` | MATCHES |
| T-01 | `t01.s8.fifth-pass-x8-cap` | IDENTICAL | `next=8` | `next=8` | MATCHES |
| T-02 | `t02.s1.classic-pass-cancels-the-round` | IDENTICAL | `GENERAL_PASS,next=1` | `GENERAL_PASS,next=1` | MATCHES |
| T-02 | `t02.s2.classic-caller-5-takes-5` | IDENTICAL | `delta=28,mult=1` | `delta=28,mult=1` | MATCHES |
| T-02 | `t02.s3.classic-never-accumulates` | IDENTICAL | `next=1,1,1` | `next=1,1,1` | MATCHES |
| T-03 | `t03.s1.full-one-saayda` | IDENTICAL | `19` | `19` | MATCHES |
| T-03 | `t03.s2.full-two-saaydas` | IDENTICAL | `20` | `20` | MATCHES |
| T-03 | `t03.s3.full-two-non-adjacent` | IDENTICAL | `20` | `20` | MATCHES |
| T-03 | `t03.s4.full-three-saaydas` | IDENTICAL | `21` | `21` | MATCHES |
| T-03 | `t03.s5.saayda-before-the-window` | IDENTICAL | `18` | `18` | MATCHES |
| T-03 | `t03.s6.mini-one-saayda` | IDENTICAL | `11` | `11` | MATCHES |
| T-03 | `t03.s7.mini-two-saaydas` | IDENTICAL | `12` | `12` | MATCHES |
| T-03 | `t03.s8.post-18-non-final-adds-nothing` | IDENTICAL | `21` | `21` | MATCHES |
| T-03 | `t03.s9.cap-removal` | IDENTICAL | `23` | `23` | MATCHES |
| T-04 | `t04.s1.round-18-saayda-adds-19` | IDENTICAL | `extend=true,reason=SAAYDA,next=2` | `extend=true,reason=SAAYDA,next=2` | MATCHES |
| T-04 | `t04.s2.round-19-saayda-adds-20` | IDENTICAL | `extend=true,reason=SAAYDA,next=4` | `extend=true,reason=SAAYDA,next=4` | MATCHES |
| T-04 | `t04.s3.round-20-saayda-adds-21` | IDENTICAL | `extend=true,reason=SAAYDA,next=6` | `extend=true,reason=SAAYDA,next=6` | MATCHES |
| T-04 | `t04.s4.round-21-success-ends-the-match` | IDENTICAL | `extend=false,next=1` | `extend=false,next=1` | MATCHES |
| T-04 | `t04.s5.round-21-saayda-adds-22` | IDENTICAL | `extend=true,reason=SAAYDA,next=8` | `extend=true,reason=SAAYDA,next=8` | MATCHES |
| T-04 | `t04.s6.tied-top-is-a-winner` | IDENTICAL | `p1,p2` | `p1,p2` | MATCHES |
| T-05 | `t05.s1.super-win` | IDENTICAL | `64` | `64` | MATCHES |
| T-05 | `t05.s2.super-win-sole` | IDENTICAL | `74` | `74` | MATCHES |
| T-05 | `t05.s3.super-loss` | IDENTICAL | `-32` | `-32` | MATCHES |
| T-05 | `t05.s4.super-loss-sole` | IDENTICAL | `-42` | `-42` | MATCHES |
| T-05 | `t05.s5.extension-on-success` | IDENTICAL | `extend=true,reason=SUPER_CALL` | `extend=true,reason=SUPER_CALL` | MATCHES |
| T-05 | `t05.s6.no-extension-on-failure` | IDENTICAL | `extend=false` | `extend=false` | MATCHES |
| T-05 | `t05.s7.classic-super-win` | IDENTICAL | `42` | `42` | MATCHES |
| T-05 | `t05.s8.classic-super-loss` | IDENTICAL | `-20` | `-20` | MATCHES |
| T-05 | `t05.s9.with-on-a-super-call` | IDENTICAL | `role=WIZZ,delta=28` | `role=WIZZ,delta=28` | MATCHES |
| T-06 | `t06.s1.dash-success` | IDENTICAL | `total=15,delta=20` | `total=15,delta=20` | MATCHES |
| T-06 | `t06.s2.dash-failure` | IDENTICAL | `total=15,delta=-22` | `total=15,delta=-22` | MATCHES |
| T-06 | `t06.s3.dash-sole-winner` | IDENTICAL | `total=15,delta=30` | `total=15,delta=30` | MATCHES |
| T-06 | `t06.s4.dash-sole-loser` | IDENTICAL | `total=11,delta=-32` | `total=11,delta=-32` | MATCHES |
| T-06 | `t06.s5.risk-20-success` | IDENTICAL | `total=17,delta=30` | `total=17,delta=30` | MATCHES |
| T-06 | `t06.s6.risk-20-failure` | IDENTICAL | `total=17,delta=-32` | `total=17,delta=-32` | MATCHES |
| T-06 | `t06.s7.risk-30-success` | IDENTICAL | `total=19,delta=40` | `total=19,delta=40` | MATCHES |
| T-06 | `t06.s8.risk-30-failure` | IDENTICAL | `total=19,delta=-42` | `total=19,delta=-42` | MATCHES |
| T-07 | `t07.s1.dash-success-under` | IDENTICAL | `total=12,delta=23` | `total=12,delta=23` | MATCHES |
| T-07 | `t07.s10.combined-sole-winner-over` | IDENTICAL | `total=15,delta=33` | `total=15,delta=33` | MATCHES |
| T-07 | `t07.s11.combined-sole-winner-under` | IDENTICAL | `total=11,delta=43` | `total=11,delta=43` | MATCHES |
| T-07 | `t07.s12.combined-sole-loser` | IDENTICAL | `total=11,delta=-22` | `total=11,delta=-22` | MATCHES |
| T-07 | `t07.s13.risk-ladder-20-failure` | IDENTICAL | `total=17,delta=-22` | `total=17,delta=-22` | MATCHES |
| T-07 | `t07.s14.risk-ladder-30-failure` | IDENTICAL | `total=19,delta=-32` | `total=19,delta=-32` | MATCHES |
| T-07 | `t07.s15.risk-ladder-20-success` | IDENTICAL | `total=17,delta=33` | `total=17,delta=33` | MATCHES |
| T-07 | `t07.s16.risk-ladder-30-success` | IDENTICAL | `total=19,delta=43` | `total=19,delta=43` | MATCHES |
| T-07 | `t07.s2.dash-success-over` | IDENTICAL | `total=15,delta=13` | `total=15,delta=13` | MATCHES |
| T-07 | `t07.s3.dash-failure-over` | IDENTICAL | `total=15,delta=-2` | `total=15,delta=-2` | MATCHES |
| T-07 | `t07.s4.dash-failure-under` | IDENTICAL | `total=12,delta=-10` | `total=12,delta=-10` | MATCHES |
| T-07 | `t07.s5.risk-component-success` | IDENTICAL | `component=+10` | `component=+10` | MATCHES |
| T-07 | `t07.s6.risk-component-failure` | IDENTICAL | `component=-10` | `component=-10` | MATCHES |
| T-07 | `t07.s7.combined-failure-over` | IDENTICAL | `total=15,delta=-12` | `total=15,delta=-12` | MATCHES |
| T-07 | `t07.s8.combined-success-over` | IDENTICAL | `total=15,delta=23` | `total=15,delta=23` | MATCHES |
| T-07 | `t07.s9.combined-success-under` | IDENTICAL | `total=11,delta=33` | `total=11,delta=33` | MATCHES |
| T-08 | `t08.r1.ahmed-5h-osama-5h` | IDENTICAL | `caller=p1,with=p2` | `caller=p1,with=p2` | MATCHES |
| T-08 | `t08.r2.osama-raises-to-6h` | IDENTICAL | `caller=p2,with=p1` | `caller=p2,with=p1` | MATCHES |
| T-08 | `t08.r3.a-matching-number-is-not-enough` | IDENTICAL | `caller=p1,with=` | `caller=p1,with=` | MATCHES |
| T-08 | `t08.r4.with-at-the-last-heart-bid` | IDENTICAL | `caller=p2,with=p1,at=6` | `caller=p2,with=p1,at=6` | MATCHES |
| T-08 | `t08.r5.with-5-takes-5` | IDENTICAL | `delta=25` | `delta=25` | MATCHES |
| T-08 | `t08.r6.with-5-misses-by-two` | IDENTICAL | `delta=-12` | `delta=-12` | MATCHES |
| T-09 | `t09.s1.step-1-call-8` | IDENTICAL | `caller=p1,top=8,trump=HEARTS,next=p2` | `caller=p1,top=8,trump=HEARTS,next=p2` | MATCHES |
| T-09 | `t09.s2.step-2-call-9` | IDENTICAL | `caller=p2,top=9,trump=SPADES,next=p3` | `caller=p2,top=9,trump=SPADES,next=p3` | MATCHES |
| T-09 | `t09.s3.step-3-call-10` | IDENTICAL | `caller=p3,top=10,trump=CLUBS,next=p4` | `caller=p3,top=10,trump=CLUBS,next=p4` | MATCHES |
| T-09 | `t09.s4.no-higher-call-ends-the-auction` | IDENTICAL | `caller=p3,trump=CLUBS` | `caller=p3,trump=CLUBS` | MATCHES |
| T-09 | `t09.s5.any-trump-may-be-chosen` | IDENTICAL | `accepted,trump=DIAMONDS` | `accepted,trump=DIAMONDS` | MATCHES |
| T-09 | `t09.s6.each-higher-call-wipes-the-other-seats` | IDENTICAL | `wiped=p1,p2,p3` | `wiped=p1,p2,p3` | MATCHES |
| T-09 | `t09.s7.the-declaration-order-is-counter-clockwise` | IDENTICAL | `order=p2,p3,p4/p3,p4,p1/p4,p1,p2` | `order=p2,p3,p4/p3,p4,p1/p4,p1,p2` | MATCHES |
| T-09 | `t09.s8.a-seat-that-re-declared-declares-again` | IDENTICAL | `carried=p1,p4` | `carried=p1,p4` | MATCHES |
| T-10 | `t10.r1.highest-9-is-with` | IDENTICAL | `caller=p1,with=p2` | `caller=p1,with=p2` | MATCHES |
| T-10 | `t10.r2.super-call-trump-unchanged` | IDENTICAL | `caller=p1,with=p2,trump=SANS` | `caller=p1,with=p2,trump=SANS` | MATCHES |
| T-10 | `t10.r3.super-call-trump-changed` | IDENTICAL | `caller=p1,with=p2,trump=HEARTS` | `caller=p1,with=p2,trump=HEARTS` | MATCHES |
| T-10 | `t10.r4.trump-changed-earlier-estimate` | IDENTICAL | `caller=p1,with=,trump=HEARTS` | `caller=p1,with=,trump=HEARTS` | MATCHES |
| T-10 | `t10.r5.number-only-is-not-with` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-10 | `t10.r6.eight-while-the-highest-is-ten` | IDENTICAL | `caller=p1,with=` | `caller=p1,with=` | MATCHES |
| T-11 | `t11.r1.two-8s-different-trumps` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-11 | `t11.r2.suit-does-not-outrank-number` | IDENTICAL | `caller=p2,top=9,trump=HEARTS` | `caller=p2,top=9,trump=HEARTS` | MATCHES |
| T-11 | `t11.r3.sans-beats-a-suit-on-a-tie` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-11 | `t11.r4.loser-of-a-suit-tie-is-not-with` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-11 | `t11.r5.seat-order-must-not-decide` | IDENTICAL | `unstable` | `unstable` | DEVIATES |
| T-11 | `t11.r6.two-8s-then-a-9` | IDENTICAL | `caller=p3` | `caller=p3` | MATCHES |
| T-11 | `t11.r7.two-8s-same-suit` | IDENTICAL | `caller=p1,with=p2` | `caller=p1,with=p2` | MATCHES |
| T-11 | `t11.r8.three-ways-on-one-number` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-11 | `t11.r9.exact-duplicate-then-a-raise` | IDENTICAL | `caller=p2,with=,trump=HEARTS` | `caller=p2,with=,trump=HEARTS` | MATCHES |
| T-12 | `t12.r1.suit-at-any-time` | IDENTICAL | `with=p1,at=5` | `with=p1,at=5` | MATCHES |
| T-12 | `t12.r2.suit-never-bid` | IDENTICAL | `with=` | `with=` | MATCHES |
| T-12 | `t12.r3.same-number-different-suit` | IDENTICAL | `with=` | `with=` | MATCHES |
| T-12 | `t12.r4.superseded-call` | IDENTICAL | `with=p1,at=5` | `with=p1,at=5` | MATCHES |
| T-12 | `t12.r5.with-scoring-makes-it` | IDENTICAL | `delta=26` | `delta=26` | MATCHES |
| T-12 | `t12.r6.with-scoring-misses-it` | IDENTICAL | `delta=-11` | `delta=-11` | MATCHES |
| T-13 | `t13.r1.all-tied` | IDENTICAL | `p1=KING,p2=KING,p3=KING,p4=KING` | `p1=KING,p2=KING,p3=KING,p4=KING` | MATCHES |
| T-13 | `t13.r2.shared-bottom-of-three` | IDENTICAL | `p1=KING,p2=KOZ,p3=KOZ,p4=KOZ` | `p1=KING,p2=KOZ,p3=KOZ,p4=KOZ` | MATCHES |
| T-13 | `t13.r3.shared-lowest` | IDENTICAL | `p1=KING,p2=SECOND,p3=KOZ,p4=KOZ` | `p1=KING,p2=SECOND,p3=KOZ,p4=KOZ` | MATCHES |
| T-13 | `t13.r4.tied-top-and-a-unique-lowest` | IDENTICAL | `p1=KING,p2=KING,p3=THIRD,p4=KOZ` | `p1=KING,p2=KING,p3=THIRD,p4=KOZ` | MATCHES |
| T-13 | `t13.r5.shared-middle` | IDENTICAL | `p1=KING,p2=SECOND,p3=SECOND,p4=KOZ` | `p1=KING,p2=SECOND,p3=SECOND,p4=KOZ` | MATCHES |
| T-13 | `t13.r6.ordinary-ladder` | IDENTICAL | `p1=KING,p2=SECOND,p3=THIRD,p4=KOZ` | `p1=KING,p2=SECOND,p3=THIRD,p4=KOZ` | MATCHES |
| T-13 | `t13.r7.rp-base-per-seat` | IDENTICAL | `king=30,second=15,third=-8,koz=-20` | `king=30,second=15,third=-8,koz=-20` | MATCHES |
| T-14 | `t14.s1.charge-on-a-passed-vote` | IDENTICAL | `Koz2` | `Koz2` | MATCHES |
| T-14 | `t14.s2.shared-koz` | IDENTICAL | `Unavailable(reason=NO_UNIQUE_KOZ)` | `Unavailable(reason=NO_UNIQUE_KOZ)` | MATCHES |
| T-14 | `t14.s3.room-mode` | IDENTICAL | `Unavailable(reason=WRONG_MODE)` | `Unavailable(reason=WRONG_MODE)` | MATCHES |
| T-14 | `t14.s4.the-vote-opens` | IDENTICAL | `Available` | `Available` | MATCHES |
| T-14 | `t14.s5.the-tally` | IDENTICAL | `status=PASSED,threshold=2` | `status=PASSED,threshold=2` | MATCHES |
| T-14 | `t14.s6.the-removal` | IDENTICAL | `seat=p4,uid=u4` | `seat=p4,uid=u4` | MATCHES |
| T-14 | `t14.s7.return-before-the-finish` | IDENTICAL | `None` | `None` | MATCHES |
| T-14 | `t14.s8.rp-uses-the-match-outcome` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-15 | `t15.s1.explicit-leave` | IDENTICAL | `ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2` | `ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2` | MATCHES |
| T-15 | `t15.s10.rd31-two-koz-rule` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-15 | `t15.s2.fifteen-timeout` | IDENTICAL | `ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2` | `ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2` | MATCHES |
| T-15 | `t15.s3.vote-kick` | IDENTICAL | `Koz2` | `Koz2` | MATCHES |
| T-15 | `t15.s4.permanent-disconnect-replacement` | IDENTICAL | `UNRANKED=KOZ2,ROOM=NONE,RANKED=null` | `UNRANKED=KOZ2,ROOM=NONE,RANKED=null` | MATCHES |
| T-15 | `t15.s5.return-before-the-finish` | IDENTICAL | `None` | `None` | MATCHES |
| T-15 | `t15.s6.seventh-bot-takeover` | IDENTICAL | `ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2` | `ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2` | MATCHES |
| T-15 | `t15.s7.server-or-quota-failure` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-15 | `t15.s8.charge-timing` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-15 | `t15.s9.abandonment` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-16 | `t16.s1.the-ballot-window` | IDENTICAL | `30` | `30` | MATCHES |
| T-16 | `t16.s10.the-ten-second-takeover-clock` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-16 | `t16.s11.the-takeover-counter` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-16 | `t16.s12.all-humans-gone` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-16 | `t16.s13.the-fifteen-timeout-counter` | NOT_IMPLEMENTED | `NOT_IMPLEMENTED` | `NOT_IMPLEMENTED` | N-A |
| T-16 | `t16.s2.the-pause-window` | IDENTICAL | `90` | `90` | MATCHES |
| T-16 | `t16.s3.two-of-three-wait` | IDENTICAL | `WAIT` | `WAIT` | MATCHES |
| T-16 | `t16.s4.two-of-three-continue` | IDENTICAL | `CONTINUE` | `CONTINUE` | MATCHES |
| T-16 | `t16.s5.no-agreement` | IDENTICAL | `NO_AGREEMENT` | `NO_AGREEMENT` | MATCHES |
| T-16 | `t16.s6.the-held-seats` | IDENTICAL | `p2,p3` | `p2,p3` | MATCHES |
| T-16 | `t16.s7.the-reclaimable-seats` | IDENTICAL | `p2,p3` | `p2,p3` | MATCHES |
| T-16 | `t16.s8.a-cancelled-ballot-is-terminal` | IDENTICAL | `CANCELLED` | `CANCELLED` | MATCHES |
| T-16 | `t16.s9.the-decision-point-clock` | IDENTICAL | `threshold=50000,one=25000:false,two=50000:true` | `threshold=50000,one=25000:false,two=50000:true` | MATCHES |
| T-22 | `t22.s1.the-pass-ladder` | IDENTICAL | `next=2,4,6,8,8` | `next=2,4,6,8,8` | MATCHES |
| T-22 | `t22.s2.the-saayda-ladder` | IDENTICAL | `next=2,4,6` | `next=2,4,6` | MATCHES |
| T-22 | `t22.s3.four-consecutive-saaydas` | IDENTICAL | `8` | `8` | MATCHES |
| T-22 | `t22.s4.a-success-resets-the-ladder` | IDENTICAL | `1` | `1` | MATCHES |
| T-22 | `t22.s5.classic-never-accumulates` | IDENTICAL | `next=1,1,1` | `next=1,1,1` | MATCHES |
| T-24 | `t24.s1.minimum-bid-three` | IDENTICAL | `illegal` | `illegal` | MATCHES |
| T-24 | `t24.s10.call-cap-fast` | IDENTICAL | `legal` | `legal` | MATCHES |
| T-24 | `t24.s11.risk-value-ten` | IDENTICAL | `10` | `10` | MATCHES |
| T-24 | `t24.s12.risk-value-twenty` | IDENTICAL | `20` | `20` | MATCHES |
| T-24 | `t24.s13.risk-value-thirty` | IDENTICAL | `30` | `30` | MATCHES |
| T-24 | `t24.s14.confirmation-weaker-suit` | IDENTICAL | `illegal` | `illegal` | MATCHES |
| T-24 | `t24.s15.confirmation-equal-or-stronger` | IDENTICAL | `legal/legal` | `legal/legal` | MATCHES |
| T-24 | `t24.s16.with-floor` | IDENTICAL | `illegal` | `illegal` | MATCHES |
| T-24 | `t24.s2.minimum-bid-four` | IDENTICAL | `legal` | `legal` | MATCHES |
| T-24 | `t24.s3.bid-seven-is-not-a-super-call` | IDENTICAL | `CALLER` | `CALLER` | MATCHES |
| T-24 | `t24.s4.bid-eight-is-a-super-call` | IDENTICAL | `SUPER_CALL` | `SUPER_CALL` | MATCHES |
| T-24 | `t24.s5.the-dash-limit` | IDENTICAL | `callers=p1,p2,third=PASS` | `callers=p1,p2,third=PASS` | DEVIATES |
| T-24 | `t24.s6.the-thirteen-rule` | IDENTICAL | `forbidden=0,zero=illegal,one=legal` | `forbidden=0,zero=illegal,one=legal` | MATCHES |
| T-24 | `t24.s7.the-thirteen-rule-scope` | IDENTICAL | `forbidden=1,zero=legal,one=illegal` | `forbidden=1,zero=legal,one=illegal` | DEVIATES |
| T-24 | `t24.s8.the-thirteen-rule-scope-at-14` | IDENTICAL | `forbidden=none,zero=legal,one=legal` | `forbidden=none,zero=legal,one=legal` | MATCHES |
| T-24 | `t24.s9.call-cap-normal` | IDENTICAL | `illegal` | `illegal` | MATCHES |

### 3.2 Coverage check — every T-ID in E3's scope appears

| T-ID | Cases | IDENTICAL | DIFFERENT | NOT IMPLEMENTED | Plan axis |
| --- | --- | --- | --- | --- | --- |
| T-01 | 8 | 8 | 0 | 0 | 8 MATCHES |
| T-02 | 3 | 3 | 0 | 0 | 3 MATCHES |
| T-03 | 9 | 9 | 0 | 0 | 9 MATCHES |
| T-04 | 6 | 6 | 0 | 0 | 6 MATCHES |
| T-05 | 9 | 9 | 0 | 0 | 9 MATCHES |
| T-06 | 8 | 8 | 0 | 0 | 8 MATCHES |
| T-07 | 16 | 16 | 0 | 0 | 16 MATCHES |
| T-08 | 6 | 6 | 0 | 0 | 6 MATCHES |
| T-09 | 8 | 8 | 0 | 0 | 8 MATCHES |
| T-10 | 6 | 5 | 0 | 1 | 5 MATCHES |
| T-11 | 9 | 5 | 0 | 4 | 4 MATCHES, 1 DEVIATES |
| T-12 | 6 | 6 | 0 | 0 | 6 MATCHES |
| T-13 | 7 | 7 | 0 | 0 | 7 MATCHES |
| T-14 | 8 | 7 | 0 | 1 | 7 MATCHES |
| T-15 | 10 | 6 | 0 | 4 | 6 MATCHES |
| T-16 | 13 | 9 | 0 | 4 | 9 MATCHES |
| T-22 | 5 | 5 | 0 | 0 | 5 MATCHES |
| T-24 | 16 | 16 | 0 | 0 | 14 MATCHES, 2 DEVIATES |
| **Total** | **153** | **139** | **0** | **14** | **136 MATCHES, 3 DEVIATES** |

No T-ID is silently dropped: each of the 19 appears above with its own outcome and count.

### 3.3 The suites that were run

All five Gradle suites were run on this branch with `--rerun-tasks --no-build-cache` (Gradle 8.14.3
ignores `--rerun` alone). The counts below are read out of the **JUnit XML**, not off the console —
the `jsNodeTest` console prints `0 passing` and the real numbers live only in the XML.

| Suite | Tests | Skipped | Failures | Errors | XML timestamps (UTC) |
| --- | --- | --- | --- | --- | --- |
| `:engine:jvmTest` | 622 | 0 | 0 | 0 | 10:36:01.901 → 10:36:02.650 |
| `:stage0:jvmTest` | 15 | 0 | 0 | 0 | 10:35:53.143 → 10:36:01.177 |
| `:services:testDebugUnitTest` | 298 | 0 | 0 | 0 | 10:36:39.852 → 10:36:40.615 |
| `:app:testDebugUnitTest` | 242 | 0 | 0 | 0 | 10:37:05.392 → 10:37:07.547 |
| `:functions:jsNodeTest` | 760 | 0 | 0 | 0 | 10:37:34.075 → 10:37:35.782 |
| **Total** | **1,937** | **0** | **0** | **0** | 2026-10-29 |

`:stage0:jvmTest` breaks down as 5 (`MatchHarnessTest`) + 8 (`RuleVectorTest`) + 2
(`RuleVectorParityTest`).

**The E0.3 rename changed nothing.** The engine suite has **622** `@Test` annotations both before
and after the rename (counted on the working tree and on `HEAD` with `git grep -c`), and the whole
diff is 19 files / 234 insertions / 234 deletions in which **every single changed line is a `fun`
declaration line** — no assertion, no expected value, no test body.

## 4. The three deviations from the plan's wording

These are **not** runtime disagreements — the JVM and workerd agree on all three. They are places
where the engine's actual behaviour differs from the plan row's literal wording. Per the brief,
they are reported, not fixed.

### 4.1 T-11 r5 — the seat order decides an equal-highest fast call

Plan (`§5.3.3` T-11, and the O-02 / A7.2 ruling "**never seat order**"): two seats declare the same
number in different suits; the canonical suit ladder must decide, and the outcome must not depend
on who declared first.

Measured: the engine's fast-call resolution takes the **first** declaration among exact duplicates,
so re-seating the same two declarations changes the Caller. The plan's expectation for this case was
`stable`; the engine produced `unstable` on **both** runtimes.

This is the defect the plan already names elsewhere (`Bidding.kt:710`, the `biddingOrder`
seat-order tie-break) — it is a **known S4 gap**, not a new finding. E3 confirms that the gap is
present in the server runtime exactly as it is on the JVM.

### 4.2 T-24 s5 — a third Dash Call is converted to a PASS, not rejected

Plan: the maximum number of Dash Calls is 2; the third is **rejected**.

Measured: the engine does not reject the third Dash Call — it **converts it to a PASS**. The cap of 2
still holds (only two Dash callers exist in the state), but the mechanism differs from the plan's
wording. Same on both runtimes.

### 4.3 T-24 s7 — the 13-rule force starts one step earlier than the plan says

Plan: when the other declarations sum to 12, there is **no** force on the last estimator.

Measured: the engine forbids the last estimator from picking 1 (which would make the total exactly
13), so the force is **one step earlier** than the plan states. The plan's own row for the sum-13
case (`t24.s6`) and the sum-14 case (`t24.s8`) match the engine exactly.

The engine's own rule is `forbiddenEstimateFor = 13 − otherSum`, so the forbidden number is 1 when
the others sum to 12, 0 when they sum to 13, and nothing when they sum to 14 — which is exactly what
the three measured rows show. The plan's scope sentence, not the engine, is the odd one out.

## 5. The 14 NOT IMPLEMENTED cases — out of scope for engine parity

Every one of these names a behaviour the plan lists that the **engine does not have**. They are
service-side rules: the backend decides them and never calls the engine, so there is no engine
value to compare between the two runtimes. They are recorded here with the missing behaviour named
precisely; **none was implemented as part of E3.**

### 5.1 The fast-round suit gap (5 cases) — T-10 r5, T-11 r1, r3, r4, r8

A fast declaration carries **the round's current trump**, never a suit of its own. The estimate
intent carries a number only, and the engine stamps every fast bid with the round's declared trump
(`Bidding.kt:25`, `:692`, `:702`).

That makes five plan rows inexpressible as engine behaviour:

| Case | Plan row that cannot be built | What is missing |
| --- | --- | --- |
| `t10.r5.number-only-is-not-with` | "9 in another trump" while the final trump is a third one | a fast declaration that carries its own suit. The reachable form of the same fact — a seat that declared the number *before* the trump changed and did not re-declare it — **is** measured (`t10.r4`), and the same number re-declared under the final trump **is** With (`t10.r3`). |
| `t11.r1.two-8s-different-trumps` | two 8s in different suits, the suit ladder decides | a different-suit fast declaration. The engine's `resolveFastCall` implements the ladder, but the state machine never produces a different-suit fast declaration. This is the S4 gap the plan names. |
| `t11.r3.sans-beats-a-suit-on-a-tie` | a Sans declaration tied with a suited one | same cause: the canonical ladder cannot decide a tie between a Sans declaration and a suited one. |
| `t11.r4.loser-of-a-suit-tie-is-not-with` | the loser of a suit tie is not With | same cause: the "loser of a suit tie" is not expressible. |
| `t11.r8.three-ways-on-one-number` | three declarations on one number, resolved by suit | same cause: "higher suit first" needs three declarations with three different suits. |

### 5.2 The settlement layer (5 cases) — T-14 s8, T-15 s7, s8, s9, s10

| Case | Plan row | What is missing, precisely |
| --- | --- | --- |
| `t14.s8.rp-uses-the-match-outcome` | does a Vote Kick departure also cost a second last-place penalty? | Whether it does is decided by the **settlement path** (`:services` / `:functions`), which never calls the engine. The engine computes a seat's RP from its placement (`t13.r7`) and has no notion of a departure penalty on top. |
| `t15.s7.server-or-quota-failure` | a server or free-quota failure is a departure | The engine has no failure-of-the-server path at all — a server failure is decided by the backend that runs the match, and the engine is never told. Its charge table covers four leave reasons and cannot express "the server failed". |
| `t15.s8.charge-timing` | *when* the charge is recorded | Timing is a settlement decision (the services layer writes the charge when the match finishes and the player did not return). The engine decides only the **amount**, from the mode and the reason alone. |
| `t15.s9.abandonment` | abandonment is a separate outcome | Abandonment is not a departure charge: the last human leaving cancels the match and every seat is recorded Koz. The engine has no abandonment path — the cancellation and the "all four seats are Koz" outcome are decided by the settlement layer. |
| `t15.s10.rd31-two-koz-rule` | RD31's 2-Koz **departure result** | RD31's departure result (a last-place result for a departed seat) lives in the statistics layer (`:services RankedStats`), which is RANKED-only and does not consult the engine. The engine prices a departure as a career Koz count (`t15.s1`–`t15.s6`) and knows nothing about a forced last-place result. |

### 5.3 The service-side timers and counters (4 cases) — T-16 s10, s11, s12, s13

| Case | Plan row | What is missing, precisely |
| --- | --- | --- |
| `t16.s10.the-ten-second-takeover-clock` | the 10 s between the decision timer and the takeover | The engine has no 10-second takeover clock of its own: the seconds between the decision timer and the takeover are counted by the services resolver (`:services TimeoutResolution`). The engine's `InactivityClock` is the RD19 **decision-point** clock (`t16.s9`), a different mechanism with a different threshold, so it cannot stand in for this row. |
| `t16.s11.the-takeover-counter` | how many takeovers a seat has survived | Counted by the services layer (`:services TimeoutResolution`) and persisted on the match document. The engine has no counter and never sees one. |
| `t16.s12.all-humans-gone` | the 60 s wait before a table with no humans is cancelled | A services timer. The engine has no abandonment path at all. |
| `t16.s13.the-fifteen-timeout-counter` | the 15-timeout counter | S61's, kept in the services layer (`:services TimeoutResolutionDb`) and persisted per uid. The engine only receives the finished removal as a leave reason and prices it (`t15.s2`). |

### 5.4 What E3 therefore does **not** prove

The parity claim covers **the engine's rules**. It does not cover the service-side rules the plan
lists under T-14, T-15 and T-16 — vote-kick RP penalties, departure-result rules, abandonment
cancellation, takeover counters and service timers. Those live in `:services` and `:functions`,
which run on a different runtime and are not exercised by this experiment. The engine-side halves
that *do* exist (the charge matrix, the ballot windows, the RD19 clock) are measured and identical.

## 6. E4 — bundle size and start-up

Measured on today's main (`dcb954e`), on the same artifacts, on this machine.

### 6.1 Bundle size

| Bundle | Raw bytes | gzip −9 bytes |
| --- | --- | --- |
| All four runtime files (engine + stdlib + dom-compat + probe harness) | 625,718 | 103,890 |
| Engine only (drop the `stage0` probe harness) | 491,636 | 77,520 |

Raw total is the number the platform's **uncompressed** limit applies to; the gzip total is
recorded because it was the binding limit until very recently.

**The limit itself changed on 2026-09-04.** Cloudflare **removed** the compressed-size limits
(3 MB Free / 10 MB paid) and now checks **only the uncompressed bundle size, at 64 MiB on every
plan**. The 625,718-byte raw bundle is therefore about **0.9%** of the limit, and the gzip number
is no longer a constraint at all — it is kept here because it is the size the artifact is actually
*transferred* at.

**But see the caveat: a real worker also carries its own glue code, and this measurement is of a
probe bundle, not of a production worker.**

Source: [Cloudflare Workers limits](https://developers.cloudflare.com/workers/platform/limits/) and
the [2026-09-04 changelog](https://developers.cloudflare.com/changelog/post/2026-09-04-increased-worker-size-limit/)
— accessed 2026-10-29.

### 6.2 Cold and warm start

16 rounds, each one a **fresh** `wrangler dev --local` process: the script waits for wrangler's
`Ready on` log line (which appears before any request), then the first request is genuinely cold.

| Measure | Median | Min | p90 | Max | Samples |
| --- | --- | --- | --- | --- | --- |
| **Cold** — first request after a fresh start (isolate boot + module evaluation) | **36.3 ms** | 16.0 ms | 50.5 ms | 54.7 ms | 16 |
| Wall clock from "Ready on" to the first response landing | 106 ms | 82 ms | 127 ms | 128 ms | 16 |
| **Warm** — the requests that follow on the same isolate | **6.5 ms** | 4.8 ms | 23.4 ms | 31.7 ms | 80 |
| Full rule-vector table (153 cases) on a warm isolate | 41.8 ms | 39.1 ms | 52.5 ms | 62.8 ms | 16 |

The cold figure is the one that matters for a user-visible first response: **a few tens of
milliseconds**, with the harness overhead (curl, the local proxy) included. Warm requests are
single-digit milliseconds.

**Crash note.** Wrangler logs `The Workers runtime crashed unexpectedly and is being restarted`
after each round. This was investigated, not assumed: the verification pass records the crash count
**before** the script stops the server it started, and it was **0 in every one of the 16 rounds**.
The message appears only when the script kills wrangler and its workerd child dies with it — it is
a shutdown artefact, not a fault inside a measured round.

### 6.3 What E4 does **not** prove

`wrangler dev --local` on one laptop is **not** a production measurement. It says nothing about a
deployed worker's isolate scheduling, network round-trip to the edge, or cold starts behind a real
traffic split. The numbers are a **feasibility floor**: the bundle fits comfortably and the engine
starts in tens of milliseconds, so nothing measured here argues against the proposed runtime. They
are not an SLO and must not be quoted as one.

## 7. E0.3 — the T-IDs are now on the test names

19 test files in `native/engine/src/jvmTest` were renamed so that the plan's vector IDs are
readable from the test name. The naming rule follows the repo's existing precedent
(`t09SequenceLetsEveryOtherSeatRedeclareAfterEachHigherCall`): the original function name, prefixed
with the lowercase vector ID.

Examples:

| Before | After | Vector |
| --- | --- | --- |
| `superWinTable` | `t05superWinTable` | T-05 |
| `dashPlusRiskOwnerCases` | `t06t07dashPlusRiskOwnerCases` | T-06 + T-07 |
| `saaydaCountFull14To19` | `t03saaydaCountFull14To19` | T-03 |
| `generalPassDoublesMultiplier` | `t01generalPassDoublesMultiplier` | T-01 |
| `estimateRejections` | `t24estimateRejections` | T-24 |
| `aMatchingNumberInAnotherSuitIsNotWith` | `t08t12aMatchingNumberInAnotherSuitIsNotWith` | T-08 + T-12 |

Four classes carry the ID as a whole-class tag: `VoteKickTest` (T-14, 31 tests), `LeaveMatchTest`
(T-15, 13), and `DisconnectVoteTest` / `InactivityClockTest` / `InactiveTakeoverTest` /
`DisconnectVoteStateTest` (T-16, 10 + 28 + 18 + 11 = 67 in total).

**The rename changes no assertion.** The whole diff is 19 files, 234 insertions and 234 deletions,
and every changed line is a `fun` declaration line. No assert, no expected value and no test body
was touched. A T-ID was added only where the test genuinely covers that vector.

## 8. Caveats

- `:stage0` is a Stage 0 probe module. **No CI workflow runs `:stage0:*`** — this is recorded
  evidence, not a gate. `RuleVectorParityTest` skips itself when the workerd document is absent, so
  a green `:stage0:jvmTest` on a machine that has never run the recipe is **not** a parity result.
- The comparison is JVM vs workerd. It says nothing about Android device behaviour; the same
  `:engine` code runs there, which is what makes the JVM leg meaningful.
- The vectors exercise the engine's rules directly. A change to the engine can move many values;
  the numbers above are reproducible only against the same commit and the same vector table.
- The plan's own expected values are not always reachable as written. Two rows in §4 are the plan
  being imprecise rather than the engine being wrong, and one (T-11 r5) is the known S4 defect.
  None of them is a runtime disagreement — that count is **zero**.
- E4's numbers are from one laptop, one network path and `wrangler dev --local`. See §6.3.

## 9. How to reproduce

```bash
# ── JVM side ────────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:jvmTest --rerun-tasks --no-build-cache
#   → native/stage0/build/stage0-parity/jvm-rule-vectors.txt

# ── Server side ─────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:compileProductionExecutableKotlinJs --rerun-tasks --no-build-cache
cp native/stage0/build/compileSync/js/main/productionExecutable/kotlin/*.js D:/cache/stage0/e3/
#   D:/cache/stage0/e3/worker.mjs calls stage0.runRuleVectors()
cd D:/cache/stage0/e3
WRANGLER_SEND_METRICS=false XDG_CONFIG_HOME=D:/cache/stage0/config \
  "C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" \
  D:/cache/stage0/node_modules/wrangler/bin/wrangler.js dev --local --port 8973 --ip 127.0.0.1
curl "http://127.0.0.1:8973/?what=rule-vectors" -o workerd-rule-vectors.txt

# ── Compare ────────────────────────────────────────────────────────────────
cmp native/stage0/build/stage0-parity/jvm-rule-vectors.txt workerd-rule-vectors.txt
sha256sum native/stage0/build/stage0-parity/jvm-rule-vectors.txt workerd-rule-vectors.txt
```

The same `:stage0:jvmTest` run also writes the verdict table
`native/stage0/build/stage0-parity/rule-vector-verdicts.txt` and `.md`, which is where §3.1 comes
from.

## 10. What the owner still has to decide

E3 is a measurement; it closes the evidence question and opens three small ones.

1. **The three deviations (§4).** T-11 r5 is the known S4 defect and needs the S4 story, not a
   ruling. T-24 s5 (a third Dash Call converted to a PASS rather than rejected) and T-24 s7 (the
   13-rule force starting one step earlier) are places where **the plan's wording and the engine's
   behaviour disagree** — one of the two has to change. The engine's behaviour is self-consistent in
   both cases and the 13-rule behaviour matches the engine's own `13 − otherSum` formula, so the
   cheaper reading is that the plan's sentences are imprecise; but that is the owner's call, not
   the report's.
2. **The 14 NOT IMPLEMENTED rows (§5).** Each names a service-side rule. They are not engine gaps
   to be filled in `:engine`; they are either already implemented in `:services`/`:functions` (and
   need their own evidence, not engine parity) or genuinely missing. The owner may want a
   **service-side parity experiment** (E3b) that exercises the same scenarios through the
   functions runtime instead of the engine.
3. **The runtime decision itself (O-06).** The ADR skeleton at `docs/adr/0003-runtime-choice.md`
   carries the evidence from E1, E2, E3 and E4 with the decision left as an explicit slot for the
   owner. E5–E13 are not measured yet.
