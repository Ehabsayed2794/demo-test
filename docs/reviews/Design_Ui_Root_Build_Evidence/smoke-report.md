# T-002 hosting-dist smoke test — 2026-09-19T18:48:02.876Z

Artifact: C:\Users\EXPRESS\OneDrive\Desktop\demo-test\hosting-dist
Server: http://127.0.0.1:5056  |  Emulator: 127.0.0.1:8080 / 127.0.0.1:9099
uids: {"p1":"un2d0y18eWastoRyc36xMgtNiIZG","p2":"MVeMJDPPCRYTiaxP5K1T3ZzvCiTc","p3":"sDG0AeR6py4o3rY09Bza4m8UuOYU","p4":"dkQaX17RFX1NckIVGJ8EVje3JnWH"}
roomId: GCWQVX  matchId: N9yVwA3u8iKll65neKy5

## Result: 21 passed, 0 failed
## Failures
(none)

## Console errors (7)
- [p3 console/error] Failed to load resource: the server responded with a status of 409 (Conflict)
- [p1 console/error] Failed to load resource: the server responded with a status of 409 (Conflict)
- [p2 console/error] Failed to load resource: the server responded with a status of 409 (Conflict)
- [p4 console/error] Failed to load resource: the server responded with a status of 409 (Conflict)
- [p2 console/error] Failed to load resource: the server responded with a status of 400 (Bad Request)
- [p1 console/error] Failed to load resource: the server responded with a status of 403 (Forbidden)
- [p1 console/error] [MatchAdapter] dealRound() attempt failed (swallowed, non-fatal): {name: FirebaseError, code: permission-denied, message: 
evaluation error at L1941:26 for 'create' @ L1941… for 'update' @ L1942, false for 'update' @ L1950, stack: FirebaseError: 
evaluation error at L1941:26 for '… for 'update' @ L1942, false for 'update' @ L1950}

## 404s (0)
(none)

## Firestore API rejections (6) — app-level findings, NOT T-002 artifact defects
- [p3 HTTP 409] http://127.0.0.1:8080/v1/projects/made---estimation-card-game/databases/(default)/documents:commit
- [p1 HTTP 409] http://127.0.0.1:8080/v1/projects/made---estimation-card-game/databases/(default)/documents:commit
- [p2 HTTP 409] http://127.0.0.1:8080/v1/projects/made---estimation-card-game/databases/(default)/documents:commit
- [p4 HTTP 409] http://127.0.0.1:8080/v1/projects/made---estimation-card-game/databases/(default)/documents:commit
- [p2 HTTP 400] http://127.0.0.1:8080/v1/projects/made---estimation-card-game/databases/(default)/documents:commit
- [p1 HTTP 403] http://127.0.0.1:8080/v1/projects/made---estimation-card-game/databases/(default)/documents:commit
