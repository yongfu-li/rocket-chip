// See LICENSE.SiFive for license details.

package freechips.rocketchip.tile

import Chisel._

import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

case object SharedMemoryTLEdge extends Field[TLEdgeOut]
case object TileKey extends Field[TileParams]
case object ResetVectorBits extends Field[Int]
case object MaxHartIdBits extends Field[Int]

trait TileParams {
  val core: CoreParams
  val icache: Option[ICacheParams]
  val dcache: Option[DCacheParams]
  val rocc: Seq[RoCCParams]
  val btb: Option[BTBParams]
}

trait HasTileParameters {
  implicit val p: Parameters
  val tileParams: TileParams = p(TileKey)

  val usingVM = tileParams.core.useVM
  val usingUser = tileParams.core.useUser || usingVM
  val usingDebug = tileParams.core.useDebug
  val usingRoCC = !tileParams.rocc.isEmpty
  val usingBTB = tileParams.btb.isDefined && tileParams.btb.get.nEntries > 0
  val usingPTW = usingVM
  val usingDataScratchpad = tileParams.dcache.flatMap(_.scratch).isDefined

  val hartIdLen = p(MaxHartIdBits)

  def dcacheArbPorts = 1 + usingVM.toInt + usingDataScratchpad.toInt + tileParams.rocc.size
}

abstract class BareTile(implicit p: Parameters) extends LazyModule

abstract class BareTileBundle[+L <: BareTile](_outer: L) extends GenericParameterizedBundle(_outer) {
  val outer = _outer
  implicit val p = outer.p
}

abstract class BareTileModule[+L <: BareTile, +B <: BareTileBundle[L]](_outer: L, _io: () => B) extends LazyModuleImp(_outer) {
  val outer = _outer
  val io = _io ()
}

/** Uses TileLink master port to connect caches and accelerators to the coreplex */
trait HasTileLinkMasterPort {
  implicit val p: Parameters
  val module: HasTileLinkMasterPortModule
  val masterNode = TLOutputNode()
  val tileBus = LazyModule(new TLXbar) // TileBus xbar for cache backends to connect to
  masterNode := tileBus.node
}

trait HasTileLinkMasterPortBundle {
  val outer: HasTileLinkMasterPort
  val master = outer.masterNode.bundleOut
}

trait HasTileLinkMasterPortModule {
  val outer: HasTileLinkMasterPort
  val io: HasTileLinkMasterPortBundle
}

/** Some other standard inputs */
trait HasExternallyDrivenTileConstants extends Bundle {
  implicit val p: Parameters
  val hartid = UInt(INPUT, p(MaxHartIdBits))
  val resetVector = UInt(INPUT, p(ResetVectorBits))
}

/** Base class for all Tiles that use TileLink */
abstract class BaseTile(tileParams: TileParams)(implicit p: Parameters) extends BareTile
    with HasTileParameters
    with HasTileLinkMasterPort {
  override lazy val module = new BaseTileModule(this, () => new BaseTileBundle(this))
}

class BaseTileBundle[+L <: BaseTile](_outer: L) extends BareTileBundle(_outer)
    with HasTileLinkMasterPortBundle
    with HasExternallyDrivenTileConstants

class BaseTileModule[+L <: BaseTile, +B <: BaseTileBundle[L]](_outer: L, _io: () => B) extends BareTileModule(_outer, _io)
    with HasTileLinkMasterPortModule
