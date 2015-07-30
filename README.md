# Grid Engine Tools

This project is a suite of utility tools for Grid Engine related products. The first targeted
version will be the Univa Grid Engine. Others may be included later with support from the community.

## Building

This project makes use of the JGDI API. The jar is not publicly available but released as part of
the Grid Engine. Thus, you will need to include this jar somehow on your classpath, e.g.:

    cd /path/to/grid-engine-tools
    mkdir lib
    ln -s -t lib $SGE_ROOT/lib/jgdi.jar
