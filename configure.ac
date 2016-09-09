#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ([2.63])
AC_INIT([grid-engine-tools], [0.4.0-SNAPSHOT], [https://github.com/wookietreiber/grid-engine-tools/issues])

# Checks for programs.
AC_PROG_AWK
AC_PROG_LN_S

AC_CHECK_PROG(SBT, sbt, sbt, [])
if test "x$SBT" = x ; then
  AC_MSG_ERROR([sbt not found.])
fi

# Checks for libraries.
AC_CHECK_FILE([$SGE_ROOT/lib/jgdi.jar], [], AC_MSG_ERROR([Grid Engine or JGDI jar not found.]))

# Checks for header files.

# Checks for typedefs, structures, and compiler characteristics.

# Checks for library functions.

AC_CONFIG_FILES([Makefile])
AC_OUTPUT
