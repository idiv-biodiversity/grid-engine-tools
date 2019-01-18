# Grid Engine Tools

This project is a suite of utility tools for Grid Engine related products. The
first targeted version will be the Univa Grid Engine. Others may be included
later with support from the community.

## Dependencies

-   JDK 1.8+
-   [sbt](http://scala-sbt.org)
-   Grid Engine, installation requires `SGE_ROOT` to be set and the `jgdi.jar`
    file to be installed at `$SGE_ROOT/lib/jgdi.jar`

All dependencies that sbt and the scala code needs will be downloaded during
the sbt run.

## Installation

Install to default prefix `/usr/local`:

```bash
sbt install
```

Install to custom prefix:

```
PREFIX=/usr sbt install
```
