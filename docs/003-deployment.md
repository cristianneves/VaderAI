# 003 — Deployment and release

**Status:** Phase 8. The build side is done and verified locally; the deploy side
is written but **has not been run against a real Fly.io account** — that needs
your credentials, and it puts a real service on the internet.

Covers turning the repo into two shipped things: an installable Windows `.exe`
and a backend container reachable over WSS. Phase checklist lives in
[`002-implementation-phases.md`](002-implementation-phases.md).

---

## What ships where

| Artifact                         | Built by                 | Lands in                |
| -------------------------------- | ------------------------ | ----------------------- |
| `VaderAI-<version>-setup.exe`    | `pnpm package:desktop`   | `apps/desktop/release/` |
| `VaderAI-<version>-portable.exe` | `pnpm package:desktop`   | `apps/desktop/release/` |
| Backend container                | `apps/server/Dockerfile` | Fly.io                  |

### Version scheme

`0.MINOR.PATCH`, where `MINOR` is the last completed phase — this is `0.8.0`.
`1.0.0` is the first release someone other than us installs. Three files carry
it and are expected to agree: the root `package.json`, `apps/desktop/package.json`
(which feeds the installer filename and the `.exe` metadata), and
`apps/server/pom.xml`.

---

## Backend

### 1. A Supabase project

The local CLI stack is for development. Production needs a hosted project:

1. Create one, and apply the migrations in `supabase/migrations/` to it
   (`supabase link --project-ref <ref>` then `supabase db push`).
2. Note the **project ref**, the **publishable (anon) key**, and the database
   password.

### 2. Deploy

```bash
cd apps/server
fly launch --no-deploy      # first time only; keeps the committed fly.toml
fly deploy
```

`fly launch` will rename the app if `vaderai-server` is taken — let it, and use
the name it picks everywhere below.

### 3. Secrets

None of these belong in `fly.toml`; `fly secrets` stores them encrypted and
restarts the app with them in the environment.

```bash
fly secrets set \
  DEEPGRAM_API_KEY=... \
  ANTHROPIC_API_KEY=... \
  SUPABASE_JWKS_URI=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json \
  SUPABASE_DB_URL=jdbc:postgresql://<db-host>:5432/postgres \
  SUPABASE_DB_USER=postgres \
  SUPABASE_DB_PASSWORD=...
```

Two things that will cost you an afternoon if you skip them:

- **The database URL is a JDBC URL**, not the `postgresql://` string Supabase
  shows in the dashboard. It needs the `jdbc:` prefix and no embedded
  credentials — those go in the two separate secrets.
- **Use the session pooler host** if the direct host does not resolve. Fly has
  IPv6 egress and Supabase's direct host is IPv6-only, so direct usually works —
  but the pooler is the fallback when it does not.

The backend connects as the service role, which **bypasses RLS**. That is by
design and is why every query is scoped by the JWT's user id in code — see
`UserScopingTest`.

### 4. Confirm it is up

```bash
curl https://<your-app>.fly.dev/actuator/health   # {"status":"UP"}
fly logs
```

TLS is terminated at Fly's edge, so `wss://<your-app>.fly.dev/v1/session` works
with a real certificate and nothing to configure. `/actuator/health` is
`permitAll` in `SecurityConfig`, which is what lets Fly's health check reach it.

### Cost note

`fly.toml` pins `min_machines_running = 1` and disables auto-stop. A cold start
would otherwise land on the first WebSocket connect, in an app whose whole point
is answering within ~1.6 s. Flip both if you would rather pay less than wait.

---

## Desktop

`VITE_*` values are **baked into the bundle at build time** — an installer is
permanently pointed at whatever backend was configured when it was built.

Create `.env.production` at the repo root (gitignored; Vite prefers it over
`.env` for a production build):

```
VITE_SUPABASE_URL=https://<project-ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<publishable key>
VITE_SERVER_WS_URL=wss://<your-app>.fly.dev/v1/session
```

Leave `VITE_SERVER_HTTP_URL` unset — it is derived from the socket URL, and
`wss://` correctly yields `https://` (`httpUrlFromWs`, covered by tests). Setting
it by hand is how a release ends up sending bearer tokens over plain HTTP.

Then:

```bash
pnpm package:desktop
```

The anon key being in the bundle is fine: it grants nothing on its own, and RLS
confines a signed-in user to their own rows. **Provider keys — Deepgram,
Anthropic — must never appear here.** They only ever live in `fly secrets`.

### SmartScreen

The build is **unsigned**. On a machine that has not seen it before, Windows
shows _"Windows protected your PC"_ and hides the Run button behind **More
info → Run anyway**. Nothing is wrong with the build; SmartScreen simply has no
reputation for an unsigned binary from an unknown publisher.

The only real fix is an OV or EV code-signing certificate. To wire one in later,
set `CSC_LINK` (path or base64 of the `.pfx`) and `CSC_KEY_PASSWORD` in the
environment — electron-builder picks them up with no config change.

---

## Smoke test on a clean VM

The point is a machine with **no developer tooling** — no Node, no JDK, no
Visual Studio redistributables beyond what Windows ships.

1. Fresh Windows 11 VM, ideally build ≥ 19041. On anything older the app now
   shows a dialog at startup explaining the overlay will be visible in shares.
2. Copy over `VaderAI-<version>-setup.exe` and install (expect SmartScreen).
3. Launch. First run should walk: **sign in → allow microphone → add background**.
4. Grant the mic when Windows asks.
5. Start listening with audio playing, and confirm the transcript fills.
6. Join a meeting from a second device, share the entire screen, and confirm the
   overlay is **absent** from the shared view.
7. Ask something with `Ctrl+Enter` and confirm an answer streams in.

Anything that goes wrong is written to `%APPDATA%\VaderAI\logs\vaderai.log`, and
native crashes land in `%APPDATA%\VaderAI\Crashpad\reports`. Nothing is uploaded
anywhere — see the note in `main/reporter.ts` about why.

---

## Rolling back

```bash
fly releases                 # list
fly deploy --image <ref>     # redeploy a previous image
```

The desktop app has no auto-update (deliberately out of scope for the MVP), so
rolling back a client means handing out an older `.exe`.
