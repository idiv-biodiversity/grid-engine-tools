package grid.engine

import com.sun.grid.jgdi.JGDIFactory

object JGDI extends JGDI

trait JGDI extends Environment {

  def jgdiUrl = s"""bootstrap://$SGE_ROOT@$SGE_CELL:$SGE_QMASTER_PORT"""

  def jgdi = JGDIFactory newInstance jgdiUrl

}
