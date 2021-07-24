import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

// https://discuss.kotlinlang.org/t/more-concise-either/22000
sealed interface Either {
  class ParaContext(val ctx: FormParser.ParaContext) : Either {
    override fun ranges() = ctx.ranges()
    override fun rest() = ctx.rest()
    override fun SUBTOTAL() = ctx.SUBTOTAL()
    override fun instructions() = ctx.instructions()
    override val amount = ctx.amount
  }

  class SepContext(val ctx: FormParser.SepContext) : Either {
    override fun ranges() = ctx.ranges()
    override fun rest() = ctx.rest()
    override fun SUBTOTAL() = ctx.SUBTOTAL()
    override fun instructions() = ctx.instructions()
    override val amount = ctx.amount
  }

  fun ranges(): FormParser.RangesContext?
  fun rest(): FormParser.RestContext?
  fun SUBTOTAL(): TerminalNode?
  fun instructions(): FormParser.InstructionsContext
  val amount: Token?
}
