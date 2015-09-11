#!/bin/bash

# ------------------------------------------------------------------------------
# options
# ------------------------------------------------------------------------------

while getopts p: param ; do
  case $param in
    p)
      export PROJECT="$OPTARG"
      ;;
    ?)
      echo "usage: $(basename $0 .sh): [-p project]" >&2
      exit 2
      ;;
  esac
done

# ------------------------------------------------------------------------------
# app
# ------------------------------------------------------------------------------

if [[ -n $PROJECT ]] ; then
  qstat -ext -urg -s p -u '*' | awk 'NR <= 2 || $11 == ENVIRON["PROJECT"] && $13 !~ /^(h|E)qw$/ { print substr($0, 0, 61) substr($0, 71, 25) substr($0, 112, 12) substr($0, 129, 21) substr($0, 196, 6) substr($0, 214, 12) substr($0, 294) }'
else
  qstat -ext -urg -s p -u '*' | awk '$13 !~ /^(h|E|Eh)qw$/ { print substr($0, 0, 61) substr($0, 71, 58) substr($0, 129, 21) substr($0, 196, 6) substr($0, 214, 12) substr($0, 294) }'
fi
