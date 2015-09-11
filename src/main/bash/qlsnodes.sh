#!/bin/bash

usage() {
  echo "usage: $(basename $0 .sh) < -u user | -j jobid >"
}

# ------------------------------------------------------------------------------
# options
# ------------------------------------------------------------------------------

while getopts hj:u: param ; do
  case $param in
    h)
      usage
      exit 0
      ;;
    j)
      export GE_JOB_ID="$OPTARG"
      ;;
    u)
      export GE_USER="$OPTARG"
      ;;
    ?)
      usage >&2
      exit 1
      ;;
  esac
done

[[ -z $GE_JOB_ID && -z $GE_USER ]] && {
  usage >&2
  exit 1
}

# ------------------------------------------------------------------------------
# app
# ------------------------------------------------------------------------------

qconf -sel |
parallel --tag "qhost -xml -j -h" |
if [[ -n $GE_USER ]] ; then
  awk '/job_owner/ && />$GE_USER</ { print $1 }'
else
  awk "/jobid='$GE_JOB_ID'/ { print \$1 }"
fi |
sort -u
