# copycat - the fast and sweet file synchronization tool

[![Build Status](https://github.com/vegardit/copycat/workflows/Build/badge.svg "GitHub Actions")](https://github.com/vegardit/copycat/actions?query=workflow%3A%22Build%22)
[![Download](https://img.shields.io/badge/Download-latest-orange.svg)](https://github.com/vegardit/copycat/releases/tag/snapshot)
[![License](https://img.shields.io/github/license/vegardit/copycat.svg?color=blue&label=License)](LICENSE.txt)
[![Changelog](https://img.shields.io/badge/History-changelog-blue)](CHANGELOG.md)
[![Maintainability](https://api.codeclimate.com/v1/badges/6f32ab9599e166bb9b59/maintainability)](https://codeclimate.com/github/vegardit/copycat/maintainability)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-v2.1%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)

**IMPORTANT Copycat is currently in alpha testing and may not work as expected! DO NOT USE WITH IMPORTANT DATA!**

1. [What is it?](#what-is-it)
1. [Installation](#installation)
1. [Installation](#usage)
1. [License](#license)


## <a name="what-is-it"></a>What is it?

Copycat is a cross platform file synchronization tool for local file systems similar to [robocopy](https://docs.microsoft.com/en-us/windows-server/administration/windows-commands/robocopy) for Windows.

It's written in Java but compiled to native binaries for Windows/Linux/MacOS using [GraalVM](https://graalvm.org).

![screen](src/site/img/screen.png)

Advantages over robocopy:
- supports excluding files/folders using relative paths and glob patterns
- cross platform support


## <a name="installation"></a>Installation

For Windows/Linux/MacOS self-contained binaries can be downloaded at https://github.com/vegardit/copycat/releases

No installation is required.


## Usage <a name="usage"></a>Usage

Copycat understands two commands:
- `sync` is used to synchronize files from one directory to another
- `watch` is used to continuously watch a directory for changes and instantly syncs the changes to a given target

### sync command

```
$ copycat sync --help

Usage: copycat sync [-hqVv] [--copy-acl] [--delete] [--delete-excluded] [--dry-run] [--exclude-hidden-files]
                    [--exclude-hidden-system-files] [--exclude-older-files] [--exclude-system-files]
                    [--ignore-errors] [--ignore-symlink-errors] [--log-errors-to-stdout] [--log-file <logFile>]
                    [--threads <threads>] [--exclude <excludes>]... [--no-log <noLog>]... SOURCE TARGET

Performs one-way recursive directory synchronization copying new files/directories.

Positional parameters:
*     SOURCE                 Directory to copy from files.
*     TARGET                 Directory to copy files to.

Options:
      --copy-acl             Copy file permissions (ACL) for newly copied files.
      --delete               Delete extraneous files/directories from target.
      --delete-excluded      Delete excluded files/directories from target.
      --dry-run              Don't perform actual synchronization.
      --exclude <excludes>   Glob pattern for files/directories to be excluded from sync.
      --exclude-hidden-files Don't synchronize hidden files.
      --exclude-hidden-system-files
                             Don't synchronize hidden system files.
      --exclude-older-files  Don't override newer files in target with older files in source.
      --exclude-system-files Don't synchronize system files.
      --ignore-errors        Continue sync when errors occur.
      --ignore-symlink-errors
                             Continue if creation of symlinks on target fails.
      --log-errors-to-stdout Log errors to stdout instead of stderr.
      --log-file <logFile>   Write console output also to the given log file..
      --no-log <noLog>       Don't log the given filesystem operation. Valid values: CREATE, MODIFY, DELETE, SCAN
  -q, --quiet                Quiet mode.
      --threads <threads>    Number of concurrent threads.
                               Default: 2
  -v, --verbose              Specify multiple -v options to increase verbosity.
                             For example `-v -v -v` or `-vvv`.
```

Example:

```batch
$ copycat sync C:\myprojects X:\myprojects --delete --threads 4
```


### watch command

```
$ copycat watch --help

Usage: copycat watch [-hqVv] [--copy-acl] [--delete-excluded] [--exclude-hidden-files] [--exclude-hidden-system-files]
                     [--exclude-system-files] [--log-errors-to-stdout] [--log-file <logFile>]
                     [--exclude <excludes>]... [--no-log <noLog>]... SOURCE TARGET

Continuously watches a directory recursively for changes and synchronizes them to another directory.

Positional parameters:
*     SOURCE                 Directory to copy from files.
*     TARGET                 Directory to copy files to.

Options:
      --copy-acl             Copy file permissions (ACL) for newly copied files.
      --delete-excluded      Delete excluded files/directories from target.
      --exclude <excludes>   Glob pattern for files/directories to be excluded from sync.
      --exclude-hidden-files Don't synchronize hidden files.
      --exclude-hidden-system-files
                             Don't synchronize hidden system files.
      --exclude-system-files Don't synchronize system files.
      --log-errors-to-stdout Log errors to stdout instead of stderr.
      --log-file <logFile>   Write console output also to the given log file..
      --no-log <noLog>       Don't log the given filesystem operation. Valid values: CREATE, MODIFY, DELETE
  -q, --quiet                Quiet mode.
  -v, --verbose              Specify multiple -v options to increase verbosity.
                             For example `-v -v -v` or `-vvv`.
```

Example:

```batch
$ copycat watch C:\myprojects X:\myprojects
```


## <a name="license"></a>License

All files are released under the [Apache License 2.0](LICENSE.txt).

Individual files contain the following tag instead of the full license text:
```
SPDX-License-Identifier: Apache-2.0
```

This enables machine processing of license information based on the SPDX License Identifiers that are available here: https://spdx.org/licenses/.
