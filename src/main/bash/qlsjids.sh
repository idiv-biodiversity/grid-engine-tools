#!/bin/bash

# TODO don't ignore task ids

[[ -n $1 ]] || {
  echo "usage: $(basename $0 .sh) node [node...]" >&2
  exit 1
}

while [[ -n $1 ]] ; do
  qhost -xml -j -h $1 | grep -oP "<job name='\K[0-9]+?(?=')"
  shift
done | sort -u
