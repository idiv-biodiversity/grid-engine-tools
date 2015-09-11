#!/bin/bash

usage() { cat << EOF
usage: $(basename $0 .sh) node from-queue to-queue
EOF
}

[[ -z $1 || -z $2 || -z $3 ]] && {
  usage
  exit 1
}

NODE=$1
FROM=$2
TO=$3

qconf -dattr queue hostlist $NODE $FROM
qconf -aattr queue hostlist $NODE $TO
