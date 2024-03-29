#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
# SPDX-License-Identifier: Apache-2.0
#
# Author: Sebastian Thomschke, Vegard IT GmbH

if [ "${1:-}" == "--help" ]; then
  echo "Starts a Bash console in a docker container"
  echo "with the current project mounted to /project with read/write permissions."
  echo
fi

/usr/bin/env bash "$(dirname ${BASH_SOURCE[0]})/run-in-docker.sh" bash "$@"
