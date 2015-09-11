#!/bin/bash

[[ "$1" = "--help" || "$1" = "-help" || "$1" = "-h" || "$1" = "-?" ]] && {
  echo "usage: $(basename $0) [queue]"
  exit 0
}

qstat -f | awk "\$1 ~ /${1:+^$1}@/ && \$3 ~ /\/0\// { print \$1 }"
