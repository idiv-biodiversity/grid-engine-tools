#!/bin/bash

case $1 in
  -? | -h | -help | --help )
    echo "usage: $(basename $0 .sh) [node...]" >&2
    exit 0
    ;;

  "")
    qselect
    ;;

  *)
    while [[ -n $1 ]] ; do
      qselect | grep -E "@$1$"
      shift
    done
    ;;
esac
