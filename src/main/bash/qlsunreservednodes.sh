#!/bin/bash

command diff \
  <(qconf -sel) \
  <(qrstat -u '*' | awk 'NR > 2 { print $1 }' | parallel qrstat -ar | grep ^granted | grep -oE '(node|idiv)[0-9]+' | sort) |
awk '$1 == "<" { print $2 }'
