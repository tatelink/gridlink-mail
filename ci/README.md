# CI — self-hosted Forgejo runner

Codeberg's *hosted* Actions runners are alpha and only for lightweight jobs, so
Sterna's CI (an Android build) runs on a **self-hosted Forgejo runner**. The
workflow is [`.forgejo/workflows/ci.yml`](../.forgejo/workflows/ci.yml): it runs
the `:core:jmap` unit tests and assembles the debug APK on every push to `main`
and on pull requests.

## One-time setup (on an always-on server with Docker)

1. **Get a runner token.** On Codeberg: the `sterna-mail` repo → **Settings → Actions →
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
  `catthehacker/ubuntu:act-22.04` (has `apt`, `curl`, `git` — enough for a
  shell-based checkout and Android SDK install).
- The workflow installs JDK 17 + the Android SDK (API 36) with plain shell, so it
  doesn't depend on third-party actions being mirrored by Codeberg.
- **The workflow uses no `uses:` steps at all, deliberately — including for the
  checkout.** See the troubleshooting entry below before "simplifying" that back
  to `actions/checkout`.

## Troubleshooting

**A job fails after ~3 seconds, at the very first step, having compiled nothing.**
Look at the job log: if it ends on `docker cp src=... dst=/var/run/act/actions/...`
followed by `Failure`, this is it. **Docker 29.7.0 stopped creating the
destination's missing parent directories when copying into a container.** To run
any action, the runner copies it into the job container under a
content-addressed path, so that copy now fails and the job dies before doing
anything. Reproduce it in one line:

```sh
cid=$(docker run -d --rm catthehacker/ubuntu:act-22.04 tail -f /dev/null)
docker cp /etc/hostname $cid:/var/run/act/x/y/    # Error: Could not find the file /var/run/act
```

Upgrading the runner does **not** fix it (checked on 12.13.2): the assumption
lives in act's copy. The path is a hash, so it cannot be pre-created in the
image either. **The fix is to use no actions**: a workflow made only of `run:`
steps has nothing to copy. That is why the checkout here is a hand-rolled
`git init` + `git fetch --depth 1` + `git checkout` — see the comment at the top
of the workflow. Hit on 2026-07-31, hours after an unattended upgrade took the
host from Docker 29.6.2 to 29.7.0.

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
- Runner version is pinned in `docker-compose.yml` (currently **12.13.2**).
  Bumping it is a one-line change plus `docker compose up -d`; an existing
  `./data/.runner` registration survived the 6.3.1 → 12.13.2 jump without a new
  token. Codeberg forces a minimum 30 s poll interval, so a job can take half a
  minute to start.
- Status badge (once a run has completed):
  `https://codeberg.org/emon/sterna-mail/actions/workflows/ci.yml/badge.svg`
