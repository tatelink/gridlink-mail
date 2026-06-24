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

## Troubleshooting

**"registration token not found"** — registration tokens are **single-use**. If a
previous attempt already consumed it, get a fresh one (and delete any half-created
runner) in Codeberg → Settings → Actions → Runners, then re-register cleanly:

```sh
docker compose down
rm -rf ./data              # drop any partial .runner state
# put the NEW token in .env, then:
docker compose up -d
```

**"all predefined address pools have been fully subnetted"** — Docker is out of
network subnets (a busy host). This setup avoids creating networks
(`network_mode: bridge` + the generated config's `container.network: "bridge"`).
Reclaim unused ones too: `docker network prune -f`.

> The runner config is generated **inside** the container — there is no
> `config.yml` to bind-mount (an earlier version did, which could create a stray
> `config.yml/` directory if the file was missing). `git pull` to get this fix.

## Notes

- Resourcing: the build needs ~2 vCPU / 4 GB RAM and downloads the Android SDK
  per run (a few hundred MB). Add SDK/Gradle caching later to speed it up.
- To stop: `docker compose down`. To re-register from scratch: delete `./data`.
- Status badge (once a run has completed):
  `https://codeberg.org/emon/sterna-mail/actions/workflows/ci.yml/badge.svg`
