package grid.engine

object Environment extends Environment

trait Environment {
  def SGE_ROOT: String = sys.env("SGE_ROOT")
  def SGE_CELL: String = sys.env("SGE_CELL")

  def SGE_QMASTER_PORT: String = sys.env("SGE_QMASTER_PORT")
}
