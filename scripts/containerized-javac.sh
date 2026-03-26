#!/bin/bash
set -euo pipefail

# This is an example script how to run the Java compiler in a container. Adapt $image to your needs and pass the
# absolute path to this script as --config.setup.javaCompilerBinaryPath to geneseer

image=amazoncorretto:11
podman_args=(
  --rm
  --userns=keep-id
  --volume "$HOME":"$HOME":Z
  --volume /tmp:/tmp:Z
  --volume "$PWD":"$PWD":Z
  --workdir "$PWD"
)

exec podman run "${podman_args[@]}" "$image" javac "$@"
