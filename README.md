# copycat - the fast and sweet file synchronization tool

[![Build Status](https://github.com/vegardit/copycat/workflows/Build/badge.svg "GitHub Actions")](https://github.com/vegardit/copycat/actions?query=workflow%3A%22Build%22)
[![Download](https://img.shields.io/badge/Download-latest-orange.svg)](https://github.com/vegardit/copycat/releases/tag/snapshot)
[![License](https://img.shields.io/github/license/vegardit/copycat.svg?color=blue&label=License)](LICENSE.txt)
[![Changelog](https://img.shields.io/badge/History-changelog-blue)](CHANGELOG.md)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-v2.0%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)

**IMPORTANT Copycat is currently in alpha testing and may not work as expected! DO NOT USE WITH IMPORTANT DATA.**

1. [What is it?](#what-is-it)
1. [Installation](#installation)
1. [License](#license)


## <a name="what-is-it"></a>What is it?

Copycat is a cross platform file synchronization tool for local file systems similar to robocopy for Windows.

It's written in Java but compiled to native binaries for Windows/Linux/MacOS using [GraalVM](https://graalvm.org).

![screen](src/site/img/screen.png)


## <a name="installation"></a>Installation

For Windows/Linux/MacOS self-contained binaries can be downloaded at https://github.com/vegardit/copycat/releases

No installation is required.


## <a name="license"></a>License

All files are released under the [Apache License 2.0](LICENSE.txt).

Individual files contain the following tag instead of the full license text:
```
SPDX-License-Identifier: Apache-2.0
```

This enables machine processing of license information based on the SPDX License Identifiers that are available here: https://spdx.org/licenses/.
