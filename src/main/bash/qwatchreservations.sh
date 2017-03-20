#!/bin/bash

MAX_ACTIVE_RESERVATIONS=$(qconf -ssconf | awk '$1 == "max_reservation" { print $2 }')

qstat -s p | awk 'NR > 2 && $5 !~ /^(h|E)qw$/ { system("qstat -j "$1) }' |
awk -v max=$MAX_ACTIVE_RESERVATIONS '

$1 == "reserve:"    { requested++ }
$1 == "reservation" { active++    }

END {

  printf "requested: %s, active: %s/%s\n", requested, active, max > "/dev/stderr"

  if (requested >= max && active < max)
    print "oO"
  else
    print "all good"

}
'
