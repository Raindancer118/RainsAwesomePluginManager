# Rain's Awesome Plugin Manager

`apt` for a PaperMC server. Install, update, enable, disable, remove and **configure** plugins
from inside the game — by command or through a full inventory GUI.

```
  ▄▀█ █▀█ █▀▄▀█   Rain's Awesome Plugin Manager
  █▀█ █▀▀ █ ▀ █   apt for your Paper server
  ▀ ▀ ▀   ▀   ▀   by Raindancer118
```

## What it does

- **Install from a link.** Hand it a URL to a jar. APM downloads it, verifies the checksum, reads
  the plugin descriptor *without loading a single class from it*, checks the declared
  `api-version` against your server, and only then puts it in place.
- **Install from a catalogue.** `/apm install luckperms` resolves against Modrinth and picks the
  newest stable build that matches your Minecraft version and loader.
- **Search in-game.** `/apm search <term>` shows catalogue hits as a clickable list; one click
  installs.
- **Edit other plugins' configs in a GUI.** Browse any plugin's `config.yml`, toggle booleans by
  clicking, type new numbers and strings, add/remove/reorder list entries. Comments in the file are
  preserved, value types are kept, and a timestamped backup is written before every change.
- **Full lifecycle.** Enable, disable (for the session or permanently), reload, update, uninstall,
  purge, and restart with a broadcast countdown.
- **Everything twice over.** Every single command has a GUI button, and every GUI button has a
  command. That is guaranteed structurally: both are thin front ends over one service class.

## Requirements

- PaperMC **26.1+** (built against `paper-api` 26.1.2)
- Java **25**

Not Folia-compatible, and it does not claim to be — APM mutates server-wide state that has no
region to schedule against.

## Install

Grab `apm-<version>.jar` from the [latest release](../../releases/latest), drop it in `plugins/`,
restart. Everything is gated behind `apm.admin`, which defaults to `op`, so there is nothing to
configure before it is safe.

## Commands

| Command | What it does |
|---|---|
| `/apm gui` | open the graphical interface |
| `/apm list` | every plugin and its state |
| `/apm info <plugin>` | details, dependencies, compatibility |
| `/apm search <term>` | search the Modrinth catalogue |
| `/apm install <url\|slug>` | download, verify and install |
| `/apm update <plugin\|--all>` | fetch a newer build of a tracked plugin |
| `/apm enable <plugin>` | switch a plugin on |
| `/apm disable <plugin> [--permanent]` | switch it off, optionally across restarts |
| `/apm reload <plugin>` | disable and enable it again |
| `/apm config <plugin>` | browse and edit its `config.yml` |
| `/apm remove <plugin> --yes` | delete its jar, keep its config |
| `/apm purge <plugin> --yes` | delete its jar and all its data |
| `/apm pending [apply]` | review deferred file operations |
| `/apm restart [seconds\|cancel] --yes` | restart with a countdown |
| `/apm reloadconfig` | re-read APM's own `config.yml` |

Destructive subcommands need `--yes` from the console, because a console cannot be shown a
confirmation screen. As a player you get one instead.

## What it will not pretend to do

Plugin management on a running JVM has hard limits. APM states them instead of hiding them:

- **Runtime unloading is impossible.** Java cannot retract loaded classes. `disable` stops a
  plugin's tasks and listeners; its classes stay resident. Removing a jar means *gone after the next
  restart*.
- **Re-enabling a disabled plugin usually fails.** Most plugins close their thread pools and
  database connections in `onDisable` and never expected `onEnable` to be called again on the same
  instance. APM warns before, and tells you to restart after.
- **Hot-loading freezes the server briefly.** Loading and enabling a plugin is main-thread work. A
  heavyweight plugin can block for seconds and trip Paper's watchdog — expected, not a crash. Turn
  `install.attempt-hot-load` off on a busy server.
- **`/apm restart` only really restarts** if your server was started through a restart wrapper
  script. Otherwise it is a shutdown.
- **A changed jar needs a restart.** `reload` re-runs a plugin's startup logic; it does not load new
  code.

## Security

Installing a plugin means executing arbitrary code with full server privileges, so:

- HTTPS is enforced by default; an optional host allow list narrows it further.
- The size cap is enforced *while streaming*, not from the `Content-Length` a remote server claims.
- SHA-512 is verified against the publisher's hash whenever the source provides one.
- Descriptors are parsed with SnakeYAML's `SafeConstructor` — no arbitrary object instantiation —
  behind a size cap and a zip-bomb guard.
- Every file name derived from remote input is sanitised; path traversal is impossible.
- Config editing is confined to a plugin's own data folder, verified by path normalisation.
- Untrusted text enters messages as MiniMessage *unparsed* placeholders, so a plugin named
  `<red>oops` cannot inject formatting.

## Build

```bash
./gradlew build      # compiles, runs tests, produces build/libs/apm-<version>.jar
```

CI builds and tests every push and pull request, republishes a rolling `latest` release from `main`,
and cuts a permanent release for every `vX.Y.Z` tag.

## Licence

MIT — see [LICENSE](LICENSE).
