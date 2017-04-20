#!/bin/bash

[[ -n $1 ]] || {
  echo "usage: $(basename $0 .sh) username"
  exit 1
}

qstat -u $1 -s p |
awk 'NR > 2 && $5 !~ /^(h|E)qw$/ { print $1 }' |
parallel --tag 'qstat -j {} | grep -E "(^(hard|parallel|reserv|binding:))|exceeds limit|cannot run in PE"'
