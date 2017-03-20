#!/bin/bash
# nagios-aware

# nagios-based exit status
nagios_exit_good=0
nagios_exit_warn=1
nagios_exit_crit=2
nagios_exit_unkn=3

# ------------------------------------------------------------------------------
# command line arguments
# ------------------------------------------------------------------------------

if [[ -z $1 ]] ; then
  echo "usage: $(basename $0 .sh) host" >&2
  exit $nagios_exit_crit
fi

host=$1

# ------------------------------------------------------------------------------
# determine registered queue instances
# ------------------------------------------------------------------------------

queue_instances="$(qlsqis $host)"

if [[ -z $queue_instances ]] ; then
  echo "no queue instances registered at host $host"
  exit $nagios_exit_warn
fi

# ------------------------------------------------------------------------------
# check queue instances
#
# - get queue instance state and humanize it
# - try to get some more information about the reason of the state
# ------------------------------------------------------------------------------

for qi in $queue_instances ; do
  # setup qis string for output
  if [[ -z $qis ]] ; then
    qis="$qi"
  else
    qis+=", $qi"
  fi

  qi_state="$(qstat -f | awk "/$qi/ { print \$6 }")"

  if [[ -n $qi_state ]] ; then

    # humanize queue state
    for (( i=0; i < ${#qi_state}; i++ )) ; do

      case ${qi_state:$i:1} in

        a)
          msg="load threshold exceeded (a)larm"

          users=$(qstat -u \* -xml -f -l hostname=$host |
                  sed '/JB_owner/!d; s?^[ \t]*\|<JB_owner>\|</JB_owner>??g' |
                  sort -u | sed ':a;N;$!ba;s/\n/, /g')

          [[ -n $users ]] && msg+=" ($users)"

          reason=$(qstat -xml -q $qi -explain a |
                   sed -e '/<load-alarm-reason>/!d' \
                       -e 's/^[ \t]*//' \
                       -e 's?<load-alarm-reason>??g' \
                       -e 's?</load-alarm-reason>??g' \
                       -e "s/&apos;/'/g" \
                       -e "s/&quot;/'/g" \
                       -e 's/alarm //g' \
                       -e 's/ load-threshold=/ threshold=/g' |
                   sort -u | sed ':a;N;$!ba;s/\n/, /g')
          [[ -n $reason ]] && msg+=" ($reason)"

          warn=y
          ;;

        A)
          msg="suspend threshold exceeded (A)larm"

          reason=$(qstat -xml -q $qi -explain A |
                   sed -e '/<load-alarm-reason>/!d' \
                       -e 's/^[ \t]*//' \
                       -e 's?<load-alarm-reason>??g' \
                       -e 's?</load-alarm-reason>??g' \
                       -e "s/&apos;/'/g" \
                       -e "s/&quot;/'/g" |
                   sort -u | sed ':a;N;$!ba;s/\n/, /g')

          [[ -n $reason ]] && msg+=" ($reason)"

          warn=y
          ;;

        d)
          msg="(d)isabled"

          jobs=$(qlsjobs $host | sed ':a;N;$!ba;s/\n/, /g')

          admin_msg=$(qstat -explain m -q $qi | grep -oE 'admin msg:.+')

          [[ -n $admin_msg ]] && msg+=" ($admin_msg)"

          if [[ -n $jobs ]] ; then
            msg+=", jobs: $jobs"
            warn=y
          else
            if [[ $qi_state =~ u ]] ; then
              msg+=", it is already down, check why it's not up"
            else
              msg+=", there are no jobs running: reboot now"
            fi
            error=y
          fi
          ;;

        E)
          msg="error (E)"

          reason=$(qstat -xml -q $qi -explain E |
                   sed -e '/<message>/!d' \
                       -e 's/^[ \t]*//' \
                       -e 's?<message>??g' \
                       -e 's?</message>??g' \
                       -e "s/&apos;/'/g" \
                       -e "s/&quot;/'/g" |
                   sort -u | sed ':a;N;$!ba;s/\n/, /g')

          [[ -n $reason ]] && msg+=" ($reason)"

          [[ -z $(qlsjobs $host) ]] &&
          msg+=" there are no jobs running: you might as well reboot"

          error=y
          ;;

        c)
          msg="(c)onfiguration ambiguous"

          reason=$(qstat -xml -q $qi -explain c |
                   sed -e '/<load-alarm-reason>/!d' \
                       -e 's/^[ \t]*//' \
                       -e 's?<load-alarm-reason>??g' \
                       -e 's?</load-alarm-reason>??g' \
                       -e "s/&apos;/'/g" \
                       -e "s/&quot;/'/g" |
                   sort -u | sed ':a;N;$!ba;s/\n/, /g')

          [[ -n $reason ]] && msg+=" ($reason)"

          warn=y
          ;;

        s)
          msg="(s)uspended"
          warn=y
          ;;

        D)
          msg="calendar disabled (D)"
          ;;

        C)
          msg="calendar suspended (C)"
          ;;

        S)
          msg="subordinate suspended (S)"
          ;;

        o)
          msg="(o)rphaned"
          warn=y
          ;;

        u)
          msg="(u)nknown"
          unknown=y
          ;;

        *)
          msg="${qi_state:$host:1}"
          error=y

      esac

      if [[ -z $msgs ]] ; then
        msgs="$msg"
      else
        msgs+=", $msg"
      fi
    done

    if [[ -z $err_qis ]] ; then
      err_qis="$qi: $msgs"
    else
      err_qis+="; $qi: $msgs"
    fi
  fi
done

[[ -n $unknown ]] && echo "$err_qis" && exit $nagios_exit_unkn
[[ -n $error   ]] && echo "$err_qis" && exit $nagios_exit_crit
[[ -n $warn    ]] && echo "$err_qis" && exit $nagios_exit_warn

echo "$qis ALL OK" && exit $nagios_exit_good
