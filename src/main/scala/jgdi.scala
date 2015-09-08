package grid.engine

import com.sun.grid.jgdi.JGDIFactory

object JGDI extends JGDI

trait JGDI {

  // -----------------------------------------------------------------------------------------------
  // environment
  // -----------------------------------------------------------------------------------------------

  def SGE_ROOT = sys.env("SGE_ROOT")
  def SGE_CELL = sys.env("SGE_CELL")
  def SGE_QMASTER_PORT = sys.env("SGE_QMASTER_PORT")

  // -----------------------------------------------------------------------------------------------
  // JGDI access
  // -----------------------------------------------------------------------------------------------

  def jgdiUrl = s"""bootstrap://$SGE_ROOT@$SGE_CELL:$SGE_QMASTER_PORT"""

  def jgdi = JGDIFactory newInstance jgdiUrl

}
