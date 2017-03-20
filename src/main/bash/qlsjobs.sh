#!/bin/bash

# TODO command line option to accept queue instances instead of nodes
# TODO command line option to include task IDs

# ------------------------------------------------------------------------------
# help / usage
# ------------------------------------------------------------------------------

function usage {
cat << EOF
usage: $(basename $0 .sh) [-?|--help] [node...]

if no arguments are given, reads from stdin
EOF
}

if [[ "$1" == "-?" || "$1" == "--help" ]] ; then
  usage
  exit
fi

# ------------------------------------------------------------------------------
# list jobs
# ------------------------------------------------------------------------------

function lsjobs {
  qhost -xml -j -h $1 | grep -oP "<job name='\K[0-9]+?(?=')"
}

if [[ -n $1 ]] ; then
  # iterate over command line arguments
  while [[ -n $1 ]] ; do
    lsjobs $1
    shift
  done
else
  # iterate over stdin lines
  while read node ; do
    [[ -n $node ]] &&
    lsjobs $node
  done < /dev/stdin
fi | sort -u
