import java.io.PrintWriter
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.html.*
import kotlinx.html.dom.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.TokenStream
import org.w3c.dom.Element

typealias Ranges = List<Triple<String, String?, String?>>

class LINE(initialAttributes: Map<String, String>, consumer: TagConsumer<*>) :
    HTMLTag("Line", consumer, initialAttributes, inlineTag = false, emptyTag = false)

class Visitor(val tokens: TokenStream) : FormBaseVisitor<List<Element>>() {
  val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

  override fun aggregateResult(a: List<Element>, b: List<Element>) = a + b

  override fun defaultResult() = listOf<Element>()

  override fun visitSection(ctx: FormParser.SectionContext) =
      listOf(
          document.create.section { h2 { +tokens.getText(ctx.start, ctx.heading().stop) } }.also {
            for (child in visitChildren(ctx)) {
              it.appendChild(child)
            }
          })

  override fun visitTable(ctx: FormParser.TableContext) =
      listOf(
          document.create.table().also {
            for (child in visitChildren(ctx)) {
              it.appendChild(child)
            }
          })

  override fun visitPara(ctx: FormParser.ParaContext) =
      mutableListOf<Element>().also {
        val seps = ctx.sep().map { Either.SepContext(it) }
        var previousCtx: Either = Either.ParaContext(ctx)
        for (sep in seps) {
          it += document.create.Line(previousCtx)
          previousCtx = sep
        }
        it += document.create.Line(previousCtx)
      }

  fun <T> TagConsumer<T>.Line(ctx: Either, block: LINE.() -> Unit = {}): T =
      LINE(
              mapOf(
                  "lines" to
                      Json.encodeToString(
                          (visit(ctx.ranges()) as Ranges).map { (line, part, form) ->
                            assert(part == null)
                            assert(form == null)
                            line
                          })),
              this)
          .visitAndFinalize(this, block)
}

fun main(args: Array<String>) {
  val (fileName) = args
  val input = CharStreams.fromFileName(fileName)
  val lexer = FormLexer(input)
  val tokens = CommonTokenStream(lexer)
  val parser = FormParser(tokens)
  val tree = parser.form()
  val writer = PrintWriter(System.out)
  for (child in Visitor(tokens).visitChildren(tree)) {
    writer.write(child)
  }
}
