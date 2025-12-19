# copycat - the fast and sweet file syncing tool

[![Build Status](https://github.com/vegardit/copycat/workflows/Build/badge.svg "GitHub Actions")](https://github.com/vegardit/copycat/actions?query=workflow%3A%22Build%22)
[![Download](https://img.shields.io/badge/Download-latest-orange.svg)](https://github.com/vegardit/copycat/releases/tag/latest)
[![License](https://img.shields.io/github/license/vegardit/copycat.svg?color=blue&label=License)](LICENSE.txt)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-v3.0%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)

1. [What is it?](#what-is-it)
1. [Installation](#installation)
1. [Quick Start](#quick-start)
1. [Usage](#usage)
   1. [`sync` command](#sync)
   1. [`watch` command](#watch)
   1. [Filters: including/excluding files](#filters)
1. [Contributing](#contributing)
1. [License](#license)


## <a name="what-is-it"></a>What is it?

Copycat is a cross platform file synchronization tool for local file systems similar to [robocopy](https://docs.microsoft.com/en-us/windows-server/administration/windows-commands/robocopy) for Windows.

It's written in Java but compiled to native binaries for Windows/Linux/MacOS using the [GraalVM - Community Edition](https://graalvm.org).

![screen](src/site/img/screen1.png)

![screen](src/site/img/screen2.png)

Advantages over robocopy:
- exclude files/folders using relative paths and glob patterns
- cross-platform support
- ANSI-colored, concise console output
- Desktop notifications and tray icon on major sync events
- YAML config for defaults and multiple tasks
- Date/time filters (`--since`, `--before`, `--until`) with natural language support


## <a name="installation"></a>Installation

For Windows/Linux/macOS, self-contained single-file binaries can be downloaded at https://github.com/vegardit/copycat/releases/latest

No installation is required.

- Windows: download the `.exe` and either run it directly or place it somewhere on your `PATH`.
- Linux/macOS: download the binary, `chmod +x copycat`, then run it (optionally move to a directory on your `PATH`, e.g. `/usr/local/bin`).

### Bash completion

Copycat provides a generated Bash completion script that you can download from https://github.com/vegardit/copycat/releases/download/latest/bashcompletion.sh

Example (Linux/macOS, or Git Bash on Windows):
```bash
curl -fsSL -o bash-completions.sh https://github.com/vegardit/copycat/releases/download/latest/bashcompletion.sh
source ./bash-completions.sh
```

To enable it permanently, source it from your `~/.bashrc` (or equivalent).


## <a name="quick-start"></a>Quick Start

- One-time sync (with deletion):
  - Windows: `copycat sync C:\src X:\dst --delete`
  - Linux/macOS: `copycat sync /src /mnt/dst --delete`
- Continuous sync (watch for changes): `copycat watch <SOURCE> <TARGET>`
- Increase verbosity with `-v`, `-vv`, `-vvv`, or use `-q` for quiet.


## <a name="usage"></a>Usage

Copycat understands two commands:
- `sync` is used to synchronize files from one directory to another
- `watch` is used to continuously watch a directory for changes and instantly syncs the changes to a given target


### <a name="sync"></a>`sync` command

```
$ copycat sync --help

Usage: copycat sync [-hqVv] [--allow-reading-open-files] [--copy-acl] [--delete] [--delete-excluded] [--dry-run]
                    [--exclude-hidden-files] [--exclude-hidden-system-files] [--exclude-older-files] [--exclude-other-links]
                    [--exclude-system-files] [--ignore-errors] [--ignore-symlink-errors] [--log-errors-to-stdout]
                    [--before <when>] [--config <path>] [--log-file <path>] [--max-depth <depth>] [--since <when>]
                    [--stall-timeout <duration>] [--threads <count>] [--until <when>] [--filter (in|ex):<pattern>]...
                    [--no-log <op>[,<op>...]]... [SOURCE] [TARGET]

Performs one-way recursive directory synchronization copying new files/directories.

Positional parameters:
      [SOURCE]              Directory to copy from files.
      [TARGET]              Directory to copy files to.

Options:
      --allow-reading-open-files
                            On Windows, open source files with shared read access (best-effort).
                              May copy an inconsistent snapshot for actively written files and may skip copying some metadata.
      --before <when>       Sync only files modified before this date/time (exclusive). Format same as --since.
                              Mutually exclusive with --until.
      --config <path>       Path to a YAML config file.
      --copy-acl            Copy file permissions (ACL) for newly copied files.
      --delete              Delete extraneous files/directories from target.
      --delete-excluded     Delete excluded files/directories from target.
      --dry-run             Don't perform actual synchronization.
      --exclude-hidden-files
                            Don't synchronize hidden files.
      --exclude-hidden-system-files
                            Don't synchronize hidden system files.
      --exclude-older-files Don't override newer files in target with older files in source.
      --exclude-other-links Don't synchronize symlinks whose targets are missing or are neither files nor directories.
      --exclude-system-files
                            Don't synchronize system files.
      --filter (in|ex):<pattern>
                            Glob pattern for files/directories to be excluded (ex:<pattern>) from or
                              included (in:<pattern>) in sync.
  -h, --help                Show this help message and exit.
      --ignore-errors       Continue sync when errors occur.
      --ignore-symlink-errors
                            Continue if creation of symlinks on target fails.
      --log-errors-to-stdout
                            Log errors to stdout instead of stderr.
      --log-file <path>     Write console output also to the given log file.
      --max-depth <depth>   Maximum directory traversal depth from the source root.
                            0=only top-level files (no subdirs), 1=include immediate subdirectories, etc.
                            Default: unlimited.
      --no-log <op>[,<op>...]
                            Don't log the given sync operation. Valid values: CREATE, MODIFY, DELETE, SCAN
  -q, --quiet               Quiet mode.
      --since <when>        Sync only files modified on or after this date/time. Accepts ISO-8601 (2024-12-25,
                              2024-12-25T14:30, 2024-12-25T14:30Z), durations (P3D, PT2H), or relative
                              expressions (3 days ago, yesterday 14:00).
      --stall-timeout <duration>
                            Abort sync if no progress is observed for this long.
                            Examples: PT10M, 10m, 2h 30m. Use 0 to disable.
                            Bare numbers are minutes. Default: 10m
      --threads <count>     Number of concurrent threads. Default: 2
      --until <when>        Sync only files modified on or before this date/time (inclusive). Format same as --since.
                              Combined with --since to define a date range. Mutually exclusive with --before.
  -v, --verbose             Specify multiple -v options to increase verbosity.
                            For example `-v -v -v` or `-vvv`.
```

Under the hood, the `sync` command uses a first-match-wins include/exclude filter engine with directory creation aligned to filtering:

- First matching `in:` / `ex:` rule wins; unmatched paths are included by default.
- Date filters (`--since`, `--before`, `--until`) apply only to files; directories are never excluded based on modification time.
- Hidden/system flags apply to both files and directories.
- Directory creation follows the filter rules:
  - Directories are created when they contain at least one included entry.
  - Empty directories are created if they are explicitly included (e.g. in:somedir) or when no filters are configured.

Examples:
```batch
:: Basic sync with deletion
copycat sync C:\myprojects X:\myprojects --delete --threads 4

:: Sync only files modified in the last 3 days
copycat sync C:\mydata X:\backup --since "3 days ago"

:: Sync files modified between specific dates
copycat sync C:\docs X:\archive --since 2024-01-01 --until 2024-12-31

:: Sync files modified since yesterday at 2 PM
copycat sync C:\work X:\backup --since "yesterday 14:00"
```

Default values and/or multiple sync tasks can be configured using a YAML config file:
```yaml
# default values for sync tasks
defaults:
  copy-acl: false
  # Optional (Windows): allow copying files that are currently open for writing by other processes (best-effort)
  # allow-reading-open-files: false
  delete: true
  delete-excluded: true
  dry-run: false
  exclude-older-files: false
  exclude-hidden-files: false
  exclude-system-files: true
  exclude-hidden-system-files: false
  filters:
    - ex:**/node_modules
    - in:logs/latest.log # keep latest log file
    - ex:logs/*.log # exclude all other log files
  ignore-errors: false
  ignore-symlink-errors: false
  threads: 2
  # Optional: limit directory traversal depth (0=no subdirs)
  # max-depth: 0
  # Optional: sync only recent files
  # since: "7 days ago"   # or "2024-01-01" or "yesterday"
  # until: "today"        # inclusive upper bound (mutually exclusive with 'before')
  # before: "tomorrow"    # exclusive upper bound (mutually exclusive with 'until')

# one or more sync tasks
sync:
- source: C:\mydata
  target: \\myserver\mydata

- source: D:\myotherdata
  target: \\myserver\myotherdata
  # Optional: sync only files modified in the last week
  since: "7 days ago"

- source: E:\archives
  target: \\backup\archives
  # Optional: sync files from a specific date range
  since: 2024-01-01
  until: 2024-12-31
```

To enable editor validation/autocompletion for `config.yaml`, a JSON schema is published at:

- https://github.com/vegardit/copycat/releases/download/latest/config.schema.json

If you use the YAML Language Server (e.g. via VS Code), you can reference it by adding this as the first line of your YAML file:

```yaml
# yaml-language-server: $schema=https://github.com/vegardit/copycat/releases/download/latest/config.schema.json
```


### <a name="watch"></a>`watch` command

```
$ copycat watch --help

Usage: copycat watch [-hqVv] [--allow-reading-open-files] [--copy-acl] [--delete-excluded] [--exclude-hidden-files]
                     [--exclude-hidden-system-files] [--exclude-system-files] [--log-errors-to-stdout]
                     [--config <path>] [--log-file <path>] [--max-depth <depth>] [--since <when>] [--until
                     <when>] [--before <when>] [--filter (in|ex):<pattern>]... [--no-log <op>[,<op>...]]...
                     [SOURCE] [TARGET]

Continuously watches a directory recursively for changes and synchronizes them to another directory.

Positional parameters:
      [SOURCE]              Directory to copy from files.
      [TARGET]              Directory to copy files to.

Options:
      --allow-reading-open-files
                            On Windows, open source files with shared read access (best-effort).
                              May copy an inconsistent snapshot for actively written files and may skip copying some metadata.
      --before <when>       Sync only files modified before this date/time (exclusive). Format same as --since.
                              Mutually exclusive with --until.
      --config <path>       Path to a YAML config file.
      --copy-acl            Copy file permissions (ACL) for newly copied files.
      --delete-excluded     Delete excluded files/directories from target.
      --exclude-hidden-files
                            Don't synchronize hidden files.
      --exclude-hidden-system-files
                            Don't synchronize hidden system files.
      --exclude-other-links Don't synchronize symlinks whose targets are neither files nor directories.
      --exclude-system-files
                            Don't synchronize system files.
      --filter (in|ex):<pattern>
                            Glob pattern for files/directories to be excluded (ex:<pattern>) from or
                              included (in:<pattern>) in sync.
  -h, --help                Show this help message and exit.
      --log-errors-to-stdout
                            Log errors to stdout instead of stderr.
      --log-file <path>     Write console output also to the given log file.
      --max-depth <depth>   Maximum directory traversal depth from the source root.
                            0=only top-level files (no subdirs), 1=include immediate subdirectories, etc.
                            Default: unlimited.
      --no-log <op>[,<op>...]
                            Don't log the given filesystem operation. Valid values: CREATE, MODIFY, DELETE
  -q, --quiet               Quiet mode.
      --since <when>        Sync only files modified on or after this date/time. Accepts ISO-8601 (2024-12-25,
                              2024-12-25T14:30, 2024-12-25T14:30Z), durations (P3D, PT2H), or relative
                              expressions (3 days ago, yesterday 14:00).
      --until <when>        Sync only files modified on or before this date/time (inclusive). Format same as
                              --since. Combined with --since to define a date range. Mutually exclusive with
                              --before.
  -v, --verbose             Specify multiple -v options to increase verbosity.
                            For example `-v -v -v` or `-vvv`.
```

Example:
```batch
$ copycat watch C:\myprojects X:\myprojects
```


Default values and/or multiple sync tasks can be configured using a YAML config file:
```yaml
# default values for sync tasks
defaults:
  copy-acl: false
  delete-excluded: true
  exclude-hidden-files: false
  exclude-system-files: true
  exclude-hidden-system-files: false
  # Optional: limit directory traversal depth for watching (0=no subdirs)
  # max-depth: 0
  filters:
    - ex:**/node_modules
    - in:logs/latest.log # keep latest log file
    - ex:logs/*.log # exclude all other log files

# one or more sync tasks
sync:
- source: C:\mydata
  target: \\myserver\mydata

- source: D:\myotherdata
  target: \\myserver\myotherdata
```


### <a name="filters"></a>Filters: including/excluding files

By default all files are synced from source to target.

#### Filter semantics

- Filters apply to both files and directories.
- Each filter is prefixed with `in:` (include) or `ex:` (exclude); the prefix is case-insensitive.
- Paths are matched against the full source-relative or target-relative path using `/` as separator; on Windows, backslashes in patterns are normalized to `/`.
- Filters are evaluated in the order they are defined; the first matching filter decides:
  - `ex:` → excluded
  - `in:` → included
- If no filter matches, the entry is included by default, even if only `in:` filters are configured.
- Date filters (`--since`, `--until`, `--before`) are applied only to files, never to directories; hidden/system flags apply to both files and directories and are evaluated before glob filters.

#### Pattern-Based Filtering

Files/folders can be excluded/included using [glob](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String)) patterns.
The patterns must be prefixed with `ex:` for exclude patterns and with `in:` for include patterns. The prefix is case-insensitive, i.e. `EX:` and `IN:` can also be used.

**The order in which patterns are declared is important.**
Copycat checks the relative path of each file to be synced against the configured list of include/exclude patterns, and **the first matching pattern is acted on**:
- if it is an exclude pattern, the file is not synced;
- if it is an include pattern, the file is synced;
- if no matching pattern is found, the file is synced.

1. YAML config example:
   ```yaml
   sync:
   - source: C:\mydata
     target: \\myserver\mydata
     filters:
       - ex:**/node_modules
       - in:logs/latest.log # keep latest log file
       - ex:logs/*.log # exclude all other log files
   ```

2. CLI example:
   ```batch
    copycat sync --filter ex:**/node_modules --filter in:logs/latest.log --filter ex:logs/*.log C:\mydata \\myserver\mydata
   ```

#### Directories and traversal

- Copycat distinguishes between:
  - *Traversal*: whether a directory is descended into.
  - *Inclusion/materialization*: whether a directory itself is created on the target.
- A global `ex:**` (or `ex:**/*`) does not by itself stop traversal when there are `in:` rules that might match descendants (for example `in:**/patch.xml, ex:**` still traverses into `dev/...` to find `patch.xml`).
- Directories on the target are created lazily:
  - A directory is created when the first included child (file or subdirectory) under it is synced, or
  - When the directory itself is explicitly included by a pattern.
- Empty directory semantics:
  - `in:somedir` includes an empty `somedir` directory (it will be created even without files).
  - `in:somedir/**` matches only descendants of `somedir`, so an empty `somedir` is not created.

#### Whitelist and blacklist recipes

- Whitelist-style (copy only some patterns):
  ```yaml
  filters:
    - in:dir/subdir/*.log
    - in:**/patch.xml
    - ex:**          # exclude everything else
  ```
- Blacklist-style (copy everything except some patterns):
  ```yaml
  filters:
    - ex:**/node_modules/**
    - ex:logs/*.log
    - in:logs/latest.log # re-include a specific file
  ```

#### Target filters and deletes

- Target-side filters use the same syntax and semantics as source filters (same `in:`/`ex:` rules, first match wins, default include).
- They are mainly used when `--delete` or `--delete-excluded` is enabled:
  - `--delete` considers target entries that do not exist in the source; filters can protect some of them.
  - `--delete-excluded` also deletes target entries that are excluded by filters.
- Matching is done on target-relative paths, again using `/` as separator (backslashes in patterns are normalized on Windows).

#### Date/Time Filtering

You can filter files based on their modification time using `--since`, `--until`, and `--before` options:

**Supported date/time formats:**
- **ISO-8601 dates**: `2024-12-25`, `2024-12-25T14:30`, `2024-12-25 14:30:45`
- **Keywords**: `yesterday`, `today`, `tomorrow` (with optional time like `yesterday 14:00`)
- **Relative expressions** (case-insensitive, including ISO-8601 durations):
  - ISO duration syntax: `[in] <duration> [ago]`, for example `PT1H`, `PT1H ago`, `in PT2H30M`, `P3D ago`
  - Human-readable syntax: `[in] <amount><unit> [<amount><unit> ...] [ago]`, for example `3h ago`, `2d 3h 15m ago`, `in 5 hours`
    - Units for human-readable forms: `d`/`day`/`days`, `h`/`hour`/`hours`, `m`/`min`/`mins`/`minute`/`minutes`, `s`/`sec`/`secs`/`second`/`seconds`
    - Order does not matter: `2d 3h`, `3h 2d`, `1h 30m`, etc.
  - Semantics:
    - `in ...` → future (relative to now), for example `in 2 hours`, `in PT2H`
    - `... ago` → past, for example `3 days ago`, `PT1H ago`
    - Without `in` or `ago` it **defaults to past**, for example `3h 30m` and `PT1H` are interpreted as *3h30m ago* and *1 hour ago* respectively.

**Examples:**
```batch
# Sync files modified in the last week
copycat sync source/ target/ --since "7 days ago"

# Sync files modified in the last hour using ISO duration
copycat sync source/ target/ --since "PT1H ago"

# Sync files modified between specific dates
copycat sync source/ target/ --since 2024-01-01 --until 2024-06-30

# Sync files modified since yesterday at 2 PM
copycat sync source/ target/ --since "yesterday 14:00"

# Sync files modified today
copycat sync source/ target/ --since today
```


## <a name="contributing"></a>Contributing

- See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.
- This project follows the Contributor Covenant; see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).


## <a name="license"></a>License

All files are released under the [Apache License 2.0](LICENSE.txt).

Individual files contain the following tag instead of the full license text:
```
SPDX-License-Identifier: Apache-2.0
```

This enables machine processing of license information based on the SPDX License Identifiers that are available here: https://spdx.org/licenses/.
