# The test account is retained — it is where the platform tests run

- **Date:** 2026-10-30
- **Status:** **DECIDED (owner).** One ruling, recorded the day E12 finished.
- **Decides:** what happens to the throwaway Cloudflare account the E12 steps ran on.
- **Supersedes:** the cleanup line in the E12 reports and in
  `2026-10-30-cadence-quota-and-e12-preparation.md` — *"the account is empty and the owner was
  asked to revoke the token."* The account is **kept**; the revoke step is **withdrawn**.

## 1. The ruling

**The throwaway account stays in place, and it is the account every platform leg runs on** —
E9's platform leg, and E12-g/h/i if they are ever run. The owner keeps it deliberately for that
purpose.

**One account, two roles, never mixed:**

| | The primary account (A) | The test account (B — this one) |
|---|---|---|
| What it is for | the product | experiments and platform legs |
| Used by Stage 0 | **never** | **always** |
| Billing | none, today | **never, by this decision** |
| Data | real users, when they exist | **none** — throwaway only |

## 2. What that changes in practice

1. **The token stays valid** (outside the repository, in `D:/cache/stage0/e12/cf.env`). It is what
   makes an approved platform leg possible without asking the owner for credentials each time.
   **No secret goes into this repository, into a report, or into a chat message** — that rule does
   not relax with this one.
2. **The harness is still removed between experiments.** Four workers and the KV namespace were
   deleted after E12; the account stays, the deployed code does not. A platform leg deploys what it
   needs and deletes it afterwards.
3. **Every platform step is still individually approved** (ruling A, 2026-10-30) — retention is
   permission to keep the account, not permission to deploy.

## 3. What does not change

- **No billing is ever enabled on B.** No paid plan, no subscription, no card. The $0 constraint is
  part of O-06 (`docs/decisions/2026-10-30-runtime-decision-o06-cloudflare.md`).
- **Account A is never touched** by any Stage 0 step.
- **E13** (account deletion and store policy) now applies **when platform testing is finished**, not
  now. Keeping the account is not a decision to keep it forever.

## 4. Why the earlier cleanup line was wrong

The E12 reports were written to close the measurement loop, and they assumed the account's job was
done. The owner's ruling names the account's real job: it is the **standing platform test surface**
for the rest of Stage 0 — and E9's platform leg is exactly the step that needs it.

## 5. Where this is pointed at

| Document | What it now says |
|---|---|
| `docs/implementation/Stage0_E12d_e_c_Platform_Meters_Report.md` §11 | the account is **retained**; the workers/KV are not |
| `docs/implementation/Stage0_E12a_Platform_Rows_Report.md` §9 | the revoke step is **withdrawn** |
| `docs/implementation/Stage0_E6_Target_Workload_Report.md` §14 | the account is **kept**, emptied of code |
| `docs/implementation/Stage0_E7b_DO_Rows_Report.md` §9 | same |
| `docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md` (pointer note) | same |
| `docs/implementation/Stage0_Remaining_Steps.md` §1.3 | E9's platform leg can run on B, step-approved |

## 6. بالعربي

الحساب التجريبي (B) **هيفضل موجود**، وهو اللي كل الاختبارات على السحابة هتتم عليه — مش هنستخدم
الحساب الأساسي (A) في أي اختبار، ومش هنفعّل أي billing على B. الـ workers والـ KV اللي اتعملوا في
E12 اتمسحوا خلاص (النسخة النظيفة)، لكن الحساب نفسه بيفضل. والتوكن بيفضل شغّال لأننا محتاجينه لأي
اختبار قادم — بس متخزّن **بره** الريبو، ومش بيتكتب في أي تقرير ولا رسالة.
