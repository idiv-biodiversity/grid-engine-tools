#!/bin/bash

qhost -q | grep -B1 -E "[0-9]+/0/[0-9]+" | grep -oE "^[^- ]+"
