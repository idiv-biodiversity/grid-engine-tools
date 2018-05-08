#!/bin/bash

function nodes.reserved {
  qrstat -u '*' |
    awk 'NR > 2 { system("qrstat -ar "$1) }' |
    awk '$1 == "granted_slots_list" { print $2 }' |
    grep -oP "[^@]+@\K[^=]+?(?==)"
}

command diff \
  <(qconf -sel | sort -u) \
  <(nodes.reserved | sort -u) |
  awk '$1 == "<" { print $2 }'
