#!/bin/bash
# nagios-aware

NAG_OK=0
NAG_WARNING=1
NAG_CRITITCAL=2
NAG_UNKNOWN=3

[[ -z "$1" ]] && {
  echo "usage: $(basename $0 .sh) node" >&2
  exit $NAG_UNKNOWN
}

queues="$(qselect | grep "@$1$")"

# no queues - bail out
if [[ -z $queues ]] ; then
  echo "no queue instances registered"
  exit $NAG_WARNING
fi

for qi in $queues ; do
  # setup qis string for output
  [[ -z $qis ]] && qis="$qi" || qis="$qis, $qi"

  qi_state="$(qstat -f | awk "\$1 ~ /^$qi$/ { print \$6 }")"

  if [[ -n "$qi_state" ]] ; then
    # humanize queue state
    for (( i=0; i < ${#qi_state}; i++ )) ; do
      case ${qi_state:$i:1} in
        a ) msg="load threshold exceeded (a)larm"

            node=$(echo $qi | cut -d@ -f2)
            users=$(qstat -u \* -xml -f -l hostname=$node | sed '/JB_owner/!d; s?^[ \t]*\|<JB_owner>\|</JB_owner>??g' | sort -u | sed ':a;N;$!ba;s/\n/, /g')
            [[ -n "$users" ]] && msg="$msg ($users)"

            reason=$(qstat -xml -q $qi -explain a | sed -n '/<load-alarm-reason>/,/<\/load-alarm-reason>/p' | sed 's?^[ \t]*\|<load-alarm-reason>\|</load-alarm-reason>??g; s/&quot;/"/g; s/alarm //g; s/ load-threshold=/ threshold=/g' | sed ':a;N;$!ba;s/\n/, /g')
            [[ -n "$reason" ]] && msg="$msg ($reason)"

            warn=y
            ;;
        A ) msg="suspend threshold exceeded (A)larm"
            reason=$(qstat -xml -q $qi -explain A | sed '/<load-alarm-reason>/!d; s?^[ \t]*\|<load-alarm-reason>\|</load-alarm-reason>??g; s/&quot;/"/g' | sed ':a;N;$!ba;s/\n/, /g')
            [[ -n "$reason" ]] && msg="$msg ($reason)"
            warn=y
            ;;
        s ) msg="(s)uspended"                        ; warn=y    ;;
        d ) msg="(d)isabled"                         ; warn=y    ;;
        D ) msg="calendar disabled (D)"                          ;;
        C ) msg="calendar suspended (C)"                         ;;
        S ) msg="subordinate suspended (S)"                      ;;
        E ) msg="error (E)"
            reason=$(qstat -xml -q $qi -explain E | sed '/<load-alarm-reason>/!d; s?^[ \t]*\|<load-alarm-reason>\|</load-alarm-reason>??g; s/&quot;/"/g' | sed ':a;N;$!ba;s/\n/, /g')
            [[ -n "$reason" ]] && msg="$msg ($reason)"
            error=y
            ;;
        c ) msg="(c)onfiguration ambiguous"
            reason=$(qstat -xml -q $qi -explain c | sed '/<load-alarm-reason>/!d; s?^[ \t]*\|<load-alarm-reason>\|</load-alarm-reason>??g; s/&quot;/"/g' | sed ':a;N;$!ba;s/\n/, /g')
            [[ -n "$reason" ]] && msg="$msg ($reason)"
            warn=y
            ;;
        o ) msg="(o)rphaned"                         ; warn=y    ;;
        u ) msg="(u)nknown"                          ; unknown=y ;;
        * ) msg="${qi_state:$1:1}"                   ; error=y
      esac
      [[ -z $msgs ]] && msgs="$msg" || msgs="$msgs, $msg"
    done

    [[ -z $err_qis ]] && err_qis="$qi: $msgs" || err_qis="$err_qis; $qi: $msgs"
  fi
done

[[ -n $unknown ]] && echo "$err_qis" && exit $NAG_UNKNOWN
[[ -n $error   ]] && echo "$err_qis" && exit $NAG_CRITICAL
[[ -n $warn    ]] && echo "$err_qis" && exit $NAG_WARNING

echo "$qis ALL OK" && exit $NAG_OK
