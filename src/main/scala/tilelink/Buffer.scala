// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import Chisel._
import chisel3.internal.sourceinfo.SourceInfo
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import scala.math.{min,max}

class TLBufferNode (
  a: BufferParams,
  b: BufferParams,
  c: BufferParams,
  d: BufferParams,
  e: BufferParams)(implicit p: Parameters) extends TLAdapterNode(
    clientFn  = { p => p.copy(minLatency = p.minLatency + b.latency + c.latency) },
    managerFn = { p => p.copy(minLatency = p.minLatency + a.latency + d.latency) }
) {
  override lazy val nodedebugstring = s"a:${a.toString}, b:${b.toString}, c:${c.toString}, d:${d.toString}, e:${e.toString}"

}

class TLBuffer(
  a: BufferParams,
  b: BufferParams,
  c: BufferParams,
  d: BufferParams,
  e: BufferParams)(implicit p: Parameters) extends LazyModule
{
  def this(ace: BufferParams, bd: BufferParams)(implicit p: Parameters) = this(ace, bd, ace, bd, ace)
  def this(abcde: BufferParams)(implicit p: Parameters) = this(abcde, abcde)
  def this()(implicit p: Parameters) = this(BufferParams.default)

  val node = new TLBufferNode(a, b, c, d, e)

  lazy val module = new LazyModuleImp(this) {
    val io = new Bundle {
      val in  = node.bundleIn
      val out = node.bundleOut
    }

    ((io.in zip io.out) zip (node.edgesIn zip node.edgesOut)) foreach { case ((in, out), (edgeIn, edgeOut)) =>
      out.a <> a(in .a)
      in .d <> d(out.d)

      if (edgeOut.manager.anySupportAcquireB && edgeOut.client.anySupportProbe) {
        in .b <> b(out.b)
        out.c <> c(in .c)
        out.e <> e(in .e)
      } else {
        in.b.valid := Bool(false)
        in.c.ready := Bool(true)
        in.e.ready := Bool(true)
        out.b.ready := Bool(true)
        out.c.valid := Bool(false)
        out.e.valid := Bool(false)
      }
    }
  }
}

object TLBuffer
{
  // applied to the TL source node; y.node := TLBuffer(x.node)
  def apply()                                   (x: TLOutwardNode)(implicit p: Parameters, sourceInfo: SourceInfo): TLOutwardNode = apply(BufferParams.default)(x)
  def apply(abcde: BufferParams)                (x: TLOutwardNode)(implicit p: Parameters, sourceInfo: SourceInfo): TLOutwardNode = apply(abcde, abcde)(x)
  def apply(ace: BufferParams, bd: BufferParams)(x: TLOutwardNode)(implicit p: Parameters, sourceInfo: SourceInfo): TLOutwardNode = apply(ace, bd, ace, bd, ace)(x)
  def apply(
      a: BufferParams,
      b: BufferParams,
      c: BufferParams,
      d: BufferParams,
      e: BufferParams)(x: TLOutwardNode)(implicit p: Parameters, sourceInfo: SourceInfo): TLOutwardNode = {
    val buffer = LazyModule(new TLBuffer(a, b, c, d, e))
    buffer.node := x
    buffer.node
  }
}

class TLBufferChain(depth: Int)(implicit p: Parameters) extends LazyModule {

  val nodeIn = TLInputNode()
  val nodeOut = TLOutputNode()

  val buf_chain = if (depth > 0) {
    val chain = List.fill(depth)(LazyModule(new TLBuffer(BufferParams.default)))

    (chain.init zip chain.tail) foreach { case(prev, next) => next.node :=* prev.node }
    chain
  } else {
    List(LazyModule(new TLBuffer(BufferParams.none)))
  }

  buf_chain.head.node :=* nodeIn
  nodeOut :=* buf_chain.last.node

  lazy val module = new LazyModuleImp(this) {
    val io = new Bundle {
      val in  = nodeIn.bundleIn
      val out = nodeOut.bundleOut
    }
  }
}
