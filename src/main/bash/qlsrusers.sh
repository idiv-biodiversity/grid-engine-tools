#!/bin/bash

qstat -s r -u '*' | awk 'NR > 2 { print $4 }' | sort -u
