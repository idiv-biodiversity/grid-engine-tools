package grid.engine

object Environment extends Environment

trait Environment {
  def SGE_ROOT = sys.env("SGE_ROOT")
  def SGE_CELL = sys.env("SGE_CELL")
  def SGE_QMASTER_PORT = sys.env("SGE_QMASTER_PORT")
}
