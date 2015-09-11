#!/bin/bash

[[ -z $1 ]] && {
  echo "usage: $(basename $0 .sh) node [node...]" >&2
  exit 1
}

while [[ -n $1 ]] ; do
  qselect -qs d | grep -E "@$1$" |
  while read qi ; do
    qmod -e $qi
  done

  shift
done
