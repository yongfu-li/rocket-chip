// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package freechips.rocketchip.coreplex

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

class BaseCoreplexConfig extends Config ((site, here, up) => {
  // Tile parameters
  case PgLevels => if (site(XLen) == 64) 3 /* Sv39 */ else 2 /* Sv32 */
  case ASIdBits => 0
  case XLen => 64 // Applies to all cores
  case MaxHartIdBits => log2Up(site(RocketTilesKey).size)
  case BuildCore => (p: Parameters) => new Rocket()(p)
  case RocketTilesKey =>  Nil // Will be added by partial configs found below
  // Interconnect parameters
  case RocketCrossing => SynchronousCrossing()
  case BroadcastParams => BroadcastParams()
  case BankedL2Params => BankedL2Params()
  case SystemBusParams => SystemBusParams(beatBytes = site(XLen)/8, blockBytes = site(CacheBlockBytes))
  case PeripheryBusParams => PeripheryBusParams(beatBytes = site(XLen)/8, blockBytes = site(CacheBlockBytes))
  case MemoryBusParams => MemoryBusParams(beatBytes = 8, blockBytes = site(CacheBlockBytes))
  case CacheBlockBytes => 64
  // Device parameters
  case DebugModuleParams => DefaultDebugModuleParams(site(XLen))
  case PLICParams => PLICParams()
  case ClintParams => ClintParams()
  case DTSTimebase => BigInt(1000000) // 1 MHz
  // TileLink connection global parameters
  case TLMonitorBuilder => (args: TLMonitorArgs) => Some(LazyModule(new TLMonitor(args)))
  case TLCombinationalCheck => false
  case TLBusDelayProbability => 0.0
})

/* Composable partial function Configs to set individual parameters */

class WithNBigCores(n: Int) extends Config((site, here, up) => {
  case RocketTilesKey => {
    val big = RocketTileParams(
      core   = RocketCoreParams(mulDiv = Some(MulDivParams(
        mulUnroll = 8,
        mulEarlyOut = true,
        divEarlyOut = true))),
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusParams).beatBits,
        nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusParams).beatBits,
        blockBytes = site(CacheBlockBytes))))
    List.fill(n)(big) ++ up(RocketTilesKey, site)
  }
})

class WithNSmallCores(n: Int) extends Config((site, here, up) => {
  case RocketTilesKey => {
    val small = RocketTileParams(
      core = RocketCoreParams(useVM = false, fpu = None),
      btb = None,
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusParams).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBEntries = 4,
        nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusParams).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBEntries = 4,
        blockBytes = site(CacheBlockBytes))))
    List.fill(n)(small) ++ up(RocketTilesKey, site)
  }
})

class WithNTinyCores(n: Int) extends Config((site, here, up) => {
    case XLen => 32
    case RocketTilesKey => {
      val tiny = RocketTileParams(
        core = RocketCoreParams(
          useVM = false,
          fpu = None,
          mulDiv = Some(MulDivParams(mulUnroll = 8))),
        btb = None,
        dcache = Some(DCacheParams(
          rowBits = site(SystemBusParams).beatBits,
          nSets = 256, // 16Kb scratchpad
          nWays = 1,
          nTLBEntries = 4,
          nMSHRs = 0,
          blockBytes = site(CacheBlockBytes),
          scratch = Some(0x80000000L))),
        icache = Some(ICacheParams(
          rowBits = site(SystemBusParams).beatBits,
          nSets = 64,
          nWays = 1,
          nTLBEntries = 4,
          blockBytes = site(CacheBlockBytes))))
    List.fill(n)(tiny) ++ up(RocketTilesKey, site)
  }
})

class WithNBanksPerMemChannel(n: Int) extends Config((site, here, up) => {
  case BankedL2Params => up(BankedL2Params, site).copy(nBanksPerChannel = n)
})

class WithNTrackersPerBank(n: Int) extends Config((site, here, up) => {
  case BroadcastParams => up(BroadcastParams, site).copy(nTrackers = n)
})

// This is the number of icache sets for all Rocket tiles
class WithL1ICacheSets(sets: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(icache = r.icache.map(_.copy(nSets = sets))) }
})

// This is the number of icache sets for all Rocket tiles
class WithL1DCacheSets(sets: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(dcache = r.dcache.map(_.copy(nSets = sets))) }
})

class WithL1ICacheWays(ways: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(icache = r.icache.map(_.copy(nWays = ways)))
  }
})

class WithL1DCacheWays(ways: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(dcache = r.dcache.map(_.copy(nWays = ways)))
  }
})

class WithCacheBlockBytes(linesize: Int) extends Config((site, here, up) => {
  case CacheBlockBytes => linesize
})

class WithBufferlessBroadcastHub extends Config((site, here, up) => {
  case BroadcastParams => up(BroadcastParams, site).copy(bufferless = true)
})

/**
 * WARNING!!! IGNORE AT YOUR OWN PERIL!!!
 *
 * There is a very restrictive set of conditions under which the stateless
 * bridge will function properly. There can only be a single tile. This tile
 * MUST use the blocking data cache (L1D_MSHRS == 0) and MUST NOT have an
 * uncached channel capable of writes (i.e. a RoCC accelerator).
 *
 * This is because the stateless bridge CANNOT generate probes, so if your
 * system depends on coherence between channels in any way,
 * DO NOT use this configuration.
 */
class WithStatelessBridge extends Config((site, here, up) => {
  case BankedL2Params => up(BankedL2Params, site).copy(coherenceManager = { case (q, _) =>
    implicit val p = q
    val cork = LazyModule(new TLCacheCork(unsafe = true))
    (cork.node, cork.node)
  })
})

class WithRV32 extends Config((site, here, up) => {
  case XLen => 32
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(
      mulDiv = Some(MulDivParams(mulUnroll = 8)),
      fpu = r.core.fpu.map(_.copy(divSqrt = false))))
  }
})

class WithNonblockingL1(nMSHRs: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(dcache = r.dcache.map(_.copy(nMSHRs = nMSHRs)))
  }
})

class WithNBreakpoints(hwbp: Int) extends Config ((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(nBreakpoints = hwbp))
  }
})

class WithRoccExample extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(rocc =
      Seq(
        RoCCParams(
          opcodes = OpcodeSet.custom0,
          generator = (p: Parameters) => LazyModule(new AccumulatorExample()(p))),
        RoCCParams(
          opcodes = OpcodeSet.custom1,
          generator = (p: Parameters) => LazyModule(new TranslatorExample()(p)),
          nPTWPorts = 1),
        RoCCParams(
          opcodes = OpcodeSet.custom2,
          generator = (p: Parameters) => LazyModule(new CharacterCountExample()(p)))
        ))
    }
})

class WithDefaultBtb extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(btb = Some(BTBParams()))
  }
})

class WithFastMulDiv extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(mulDiv = Some(
      MulDivParams(mulUnroll = 8, mulEarlyOut = (site(XLen) > 32), divEarlyOut = true)
  )))}
})

class WithoutMulDiv extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(mulDiv = None))
  }
})

class WithoutFPU extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(fpu = None))
  }
})

class WithFPUWithoutDivSqrt extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(fpu = r.core.fpu.map(_.copy(divSqrt = false))))
  }
})

class WithBootROMFile(bootROMFile: String) extends Config((site, here, up) => {
  case BootROMParams => up(BootROMParams, site).copy(contentFileName = bootROMFile)
})

class WithSynchronousRocketTiles extends Config((site, here, up) => {
  case RocketCrossing => SynchronousCrossing()
})

class WithAynchronousRocketTiles(depth: Int, sync: Int) extends Config((site, here, up) => {
  case RocketCrossing => AsynchronousCrossing(depth, sync)
})

class WithRationalRocketTiles extends Config((site, here, up) => {
  case RocketCrossing => RationalCrossing()
})

class WithEdgeDataBits(dataBits: Int) extends Config((site, here, up) => {
  case MemoryBusParams => up(MemoryBusParams, site).copy(beatBytes = dataBits/8)
  case ExtIn => up(ExtIn, site).copy(beatBytes = dataBits/8)
  
})

class WithJtagDTM extends Config ((site, here, up) => {
  case IncludeJtagDTM => true
})

class WithNoPeripheryArithAMO extends Config ((site, here, up) => {
  case PeripheryBusParams => up(PeripheryBusParams, site).copy(arithmetic = false)
})

class WithNBitPeripheryBus(nBits: Int) extends Config ((site, here, up) => {
  case PeripheryBusParams => up(PeripheryBusParams, site).copy(beatBytes = nBits/8)
})

class WithoutTLMonitors extends Config ((site, here, up) => {
  case TLMonitorBuilder => (args: TLMonitorArgs) => None
})

class WithNExtTopInterrupts(nExtInts: Int) extends Config((site, here, up) => {
  case NExtTopInterrupts => nExtInts
})

class WithNMemoryChannels(n: Int) extends Config((site, here, up) => {
  case BankedL2Params => up(BankedL2Params, site).copy(nMemoryChannels = n)
})

class WithExtMemSize(n: Long) extends Config((site, here, up) => {
  case ExtMem => up(ExtMem, site).copy(size = n)
})

class WithDTS(model: String, compat: Seq[String]) extends Config((site, here, up) => {
  case DTSModel => model
  case DTSCompat => compat
})

class WithTimebase(hertz: BigInt) extends Config((site, here, up) => {
  case DTSTimebase => hertz
})
