#!/bin/bash
set -euo pipefail

# This is an example script how to run the JVM for test execution in a container. Adapt $image to your needs and pass
# the absolute path to this script as --config.setup.jvmBinaryPath to geneseer

image=amazoncorretto:11
podman_args=(
  --rm
  --interactive
  --userns=keep-id
  --volume "$HOME":"$HOME":Z
  --volume /tmp:/tmp:Z
  --volume "$PWD":"$PWD":Z
  --workdir "$PWD"
)

java_args=()
# if the command line parameters contain a jacoco javaagent with an open TCP port, we need to expose this port outside
# of the container; so we check for that here:
jacoco_port=""
for arg in "$@"
do
    if [[ "$arg" == -javaagent:*jacocoagent.jar=*output=tcpserver,port=* ]]
    then
        jacoco_port="${arg##*port=}"
        jacoco_port="${jacoco_port%%,*}"
        arg="${arg},address=*"
    fi
    java_args+=("$arg")
done
if [[ -n "$jacoco_port" ]]
then
    podman_args+=(--publish "127.0.0.1:${jacoco_port}:${jacoco_port}")
fi

exec podman run "${podman_args[@]}" "$image" java "${java_args[@]}"
