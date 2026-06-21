# CI — self-hosted Forgejo runner

Codeberg's *hosted* Actions runners are alpha and only for lightweight jobs, so
Jmail's CI (an Android build) runs on a **self-hosted Forgejo runner**. The
workflow is [`.forgejo/workflows/ci.yml`](../.forgejo/workflows/ci.yml): it runs
the `:core:jmap` unit tests and assembles the debug APK on every push to `main`
and on pull requests.

## One-time setup (on an always-on server with Docker)

1. **Get a runner token.** On Codeberg: the `jmail` repo → **Settings → Actions →
   Runners → "Create new runner"** → copy the **registration token**.

2. **Start the runner** (from this `ci/` folder, copied to the server):

   ```sh
   RUNNER_TOKEN=<paste-token> docker compose up -d
   ```

   The runner registers once (state is kept in `./data/.runner`), then runs as a
   daemon and `restart: always` survives reboots. It mounts the Docker socket so
   it can launch a job container per run.

3. **Verify.** In Codeberg → Settings → Actions → Runners the runner shows
   **online**. Push a commit (or open a PR) and the run appears under the repo's
   **Actions** tab.

## How it works

- The runner advertises the label **`docker`**, mapped to the image
  `catthehacker/ubuntu:act-22.04` (has `apt`, `curl`, `node` — enough for
  `actions/checkout` and a shell-based Android SDK install).
- The workflow installs JDK 17 + the Android SDK (API 36) with plain shell, so it
  doesn't depend on third-party actions being mirrored by Codeberg.

## Notes

- Resourcing: the build needs ~2 vCPU / 4 GB RAM and downloads the Android SDK
  per run (a few hundred MB). Add SDK/Gradle caching later to speed it up.
- To stop: `docker compose down`. To re-register from scratch: delete `./data`.
- Status badge (once a run has completed):
  `https://codeberg.org/emon/jmail/actions/workflows/ci.yml/badge.svg`
