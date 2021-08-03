import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenStream
import org.antlr.v4.runtime.TokenStreamRewriter
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode

typealias Ranges = List<Triple<String, String?, EvalVisitor?>>

data class Range(val lines: List<String>, val part: String?, val form: EvalVisitor?)

fun String.normalize(): String {
  val key =
      replace("""\s+""".toRegex(), " ")
          .lowercase()
          .removePrefix("canada ")
          .removePrefix("british columbia ")
          .replace("""\brrsp/prpp\b""".toRegex(), "rrsp")
          .replace("""\bare you a canadian citizen\b""".toRegex(), "have canadian citizenship")
          .replace(
              """\bown or hold foreign property\b""".toRegex(),
              "own or hold specified foreign property")
  return when (key) {
    "amount for an infirm dependant aged 18 or older" ->
        "amount for infirm dependants age 18 or older"
    "disability amount for self" -> "disability amount"
    "unused contributions available to carry forward to a future year" ->
        "unused rrsp contributions available to carry forward to a future year"
    else -> key
  }
}

fun String.toAmount(): BigDecimal =
    if (endsWith("%")) Percentage(removeSuffix("%").toBigDecimal().movePointLeft(2).toString())
    else removePrefix("$").replace(",", "").toBigDecimal()

fun Any.formatAmount() =
    if (this is Percentage) "${movePointRight(2)}%"
    else
        try {
          // The value will be rounded using the round half up algorithm
          "$%,.2f".format(if (this is BigDecimal) setScale(2, RoundingMode.HALF_EVEN) else this)
        } catch (e: IllegalArgumentException) {
          this
        }

fun Properties.employmentIncome(): BigDecimal =
    (get("employment income") as String?)?.toAmount() ?: 0.toBigDecimal()

fun Properties.medicalExpensesForSelf(): BigDecimal =
    (get("medical expenses for self") as String?)?.toAmount() ?: 0.toBigDecimal()

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

class IllegalExprException : Exception()

// https://github.com/Kotlin/kotlinx.coroutines/issues/2664#issuecomment-864118975
suspend fun Iterable<Deferred<Boolean>>.all() =
    @OptIn(ExperimentalCoroutinesApi::class)
    map { @OptIn(FlowPreview::class) it::await.asFlow() }.merge().firstOrNull { !it } == null

suspend fun Iterable<Deferred<Boolean>>.any() =
    @OptIn(ExperimentalCoroutinesApi::class)
    map { @OptIn(FlowPreview::class) it::await.asFlow() }.merge().firstOrNull { it } != null

// https://discuss.kotlinlang.org/t/extend-existing-class-by-delegating-to-a-specified-object/22099
operator fun Interval.iterator() = (a..b).iterator()

operator fun Any?.compareTo(other: Any?) =
    ((this as BigDecimal?)?.setScale(2, RoundingMode.HALF_EVEN) ?: 0.toBigDecimal()).compareTo(
        (other as BigDecimal?)?.setScale(2, RoundingMode.HALF_EVEN) ?: 0.toBigDecimal())

val formsLock = ReentrantLock()
val forms = mutableMapOf<File, EvalVisitor>()
val trees = mutableMapOf<File, Pair<TokenStream, FormParser.FormContext>>()
val overrides = mutableMapOf<File, MutableMap<String, Any?>>()
val completeFormsLock = ReentrantLock()

class EvalVisitor(file: File, val scope: CoroutineScope) : FormBaseVisitor<Any?>() {
  val directory = file.parentFile
  val year = directory.parent.toInt()
  val props = Properties()
  val information = mutableMapOf<String, Any?>()

  val internal = mutableMapOf<Pair<String, String?>, Deferred<Any?>?>()
  val externalLock = ReentrantLock()
  val external = mutableMapOf<String, CompletableDeferred<Any?>>()
  val assigns = mutableMapOf<String, MutableList<Pair<Deferred<Boolean>, Deferred<Any?>?>>>()
  var subtotal: Deferred<Any?>? = null
  lateinit var currentOperand: Deferred<BigDecimal?>
  var currentLines: List<String>? = null
  var currentPart: String? = null
  var dontCompleteLines: List<String>? = null
  val useColumn = mutableMapOf<String, Deferred<Boolean>>()
  var columnCondition: Deferred<Boolean>? = null
  val previousConditions = mutableListOf<Deferred<Boolean>>()
  val completeForms = mutableListOf<EvalVisitor>()

  init {
    if (!scope.isActive) {
      throw IllegalStateException()
    }
    forms[file] = this
    val propsFile = File("${directory.name}.properties")
    props.load(propsFile.inputStream())
    val yearPropsFile = File("$year/${propsFile.name}")
    if (yearPropsFile.exists()) {
      props.load(yearPropsFile.inputStream())
    }
  }

  val yourReturn = run {
    val basename = "5010-r-%02de.txt".format(year % 100)
    scope.evalForm(File(directory, basename))!!
  }
  val spousesReturn = run {
    val basename = "5010-r-%02de.txt".format(year % 100)
    scope.evalForm(File(directory.parentFile, "${props["spouse"]}/$basename"))
  }
  var theirReturn: EvalVisitor? = null
  val jurisdiction =
      if (this == yourReturn || file.name.startsWith("5000-"))
          listOf(
              yourReturn,
              run {
                val basename = "5000-s1-%02de.txt".format(year % 100)
                scope.evalForm(File(directory, basename))
              },
          )
      else
          listOf(
              run {
                val basename = "5010-c-%02de.txt".format(year % 100)
                scope.evalForm(File(directory, basename))
              },
          )

  init {
    for (basename in
        listOf(
            "5010-d-%02de.txt".format(year % 100),
            "t2204-%02de.txt".format(year % 100), // Employee overpayment
        )) {
      val form = scope.evalForm(File(directory, basename))
      completeFormsLock.lock()
      try {
        if (form != null && !form.dependsOn(this)) {
          completeForms += form
        }
      } finally {
        completeFormsLock.unlock()
      }
    }
  }

  val medicalExpensesForSelf =
      spousesReturn?.let {
        if (directory < spousesReturn.directory) null
        else props.medicalExpensesForSelf() + spousesReturn.props.medicalExpensesForSelf()
      }

  val rewriterLock = Mutex()
  lateinit var rewriter: TokenStreamRewriter
  var isActive = true
  val job: Job =
      scope.launch {
        if (spousesReturn == null) {
          props["spouse or common-law partner amount"] = "false"
        }
        val maritalStatus = if (spousesReturn != null) "living common-law" else "single"
        information[maritalStatus] = "✔️"
        for ((key, text) in @Suppress("UNCHECKED_CAST") (props as Map<String, String>)) {
          if (key != "spouse" &&
              text != "false" &&
              (this@EvalVisitor == yourReturn ||
                  key !in
                      listOf(
                          "first name and initial",
                          "last name",
                          "mailing address",
                          "city",
                          "province/territory",
                          "postal code",
                          "email address",
                          "enter your province or territory of residence",
                          "social insurance number",
                          "your date of birth",
                          "language of correspondence",
                          "have canadian citizenship",
                          "update the national register of electors",
                          "applying for the gst/hst credit",
                          "own or hold specified foreign property",
                      ))) {
            information[key] =
                try {
                  text.toAmount()
                } catch (e: IllegalArgumentException) {
                  text
                }
          }
        }
        spousesReturn?.also { information["medical expenses for self"] = medicalExpensesForSelf }
        overrides[directory]?.also { information.putAll(it) }
        overrides[file]?.also { information.putAll(it) }

        val (tokens, tree) =
            trees.getOrPut(file) {
              val text = file.readText().replace("""\{\+.*?\+}""".toRegex(), "^")
              val input = CharStreams.fromString(text)
              val lexer = FormLexer(input)
              val tokens = CommonTokenStream(lexer)
              val parser = FormParser(tokens)
              val tree = parser.form()
              tokens to tree
            }
        rewriter = TokenStreamRewriter(tokens)
        formsLock.lock()
        formsLock.unlock()
        for (form in completeForms) {
          form.job.join()
        }
        tree.sequence()
        externalLock.lock()
        try {
          this@EvalVisitor.isActive = false
        } finally {
          externalLock.unlock()
        }
        for ((_, deferred) in external - internal.map { (key) -> key.first }) {
          deferred.complete(null)
        }
      }

  override fun visitSection(ctx: FormParser.SectionContext): Job? {
    ctx.ranges()?.also {
      val lines =
          @Suppress("UNCHECKED_CAST")
          (visit(it) as Ranges).map { (line, part, form) ->
            assert(part == null)
            assert(form == null)
            line
          }
      if (lines.any { it.toInt() >= 44 }) {
        internal.clear()
        subtotal = null
        theirReturn = null
      }
    }
    ctx.part()?.also { currentPart = it.id.text.normalize() }
    val key =
        rewriter
            .tokenStream
            .getText(ctx.heading())
            .replace("""\s*[(:].*""".toRegex(RegexOption.DOT_MATCHES_ALL), "")
            .normalize()
    return if (props[key] == "false") null else scope.launch { ctx.sequence() }
  }

  override fun visitTable(ctx: FormParser.TableContext) = scope.launch { ctx.sequence() }

  override fun visitColumn(ctx: FormParser.ColumnContext) =
      scope.launch {
        columnCondition = useColumn[ctx.id.text.normalize()]
        ctx.sequence()
        columnCondition = null
      }

  suspend fun ParseTree.sequence() {
    for (i in 0..childCount - 1) {
      (visit(getChild(i)) as? Job)?.join()
    }
  }

  override fun visitPara(ctx: FormParser.ParaContext): Job? {
    val key =
        rewriter
            .tokenStream
            .getText(ctx.instructions())
            .replace("""\s*[\n(:].*""".toRegex(RegexOption.DOT_MATCHES_ALL), "")
            .normalize()
    val seps = ctx.sep().map { Either.SepContext(it) }
    return if (props[key] == "false" ||
        key == "applicable number of months" &&
            (seps.firstOrNull() ?: Either.ParaContext(ctx)).amount?.text !=
                props["number of months during which the cpp applies"])
        null
    else
        scope.launch {
          var previousCtx: Either = Either.ParaContext(ctx)
          for (sep in seps) {
            scope.evalLine(previousCtx, sep.amount)
            previousCtx = sep
          }
          scope.evalLine(previousCtx, ctx.amount)
        }
  }

  suspend fun CoroutineScope.evalLine(ctx: Either, amount: Token?) {
    val ranges = ctx.ranges()
    ranges?.also {
      currentLines =
          @Suppress("UNCHECKED_CAST")
          (visit(ranges) as Ranges).map { (line, part, form) ->
            assert(part == null)
            assert(form == null)
            line
          }
      previousConditions.clear()
    }
    val instructions = ctx.instructions()
    // expr first for employment income
    val expr =
        ctx.SUBTOTAL()
            ?: try {
              val default = subtotal
              val rest = ctx.rest()
              if (rest != null)
                  rest.also {
                    val bound = visit(rest) as Deferred<Any?>?
                    subtotal = ifConditionOrDefault(default) { bound?.await() }
                  }
              else
                  instructions
                      .instruction()
                      .asSequence()
                      .mapNotNull { instruction ->
                        instruction.expr()?.also {
                          val bound = visit(it) as Deferred<Any?>?
                          subtotal = instruction.ifConditionOrDefault(default) { bound?.await() }
                        }
                      }
                      .firstOrNull()
            } catch (e: IllegalExprException) {
              null
            }
    var isInserted = expr != null
    if (!isInserted && (ranges != null || amount != null)) {
      val matchResult =
          if (information.isEmpty()) null
          else
              """(?<!-)\b(?:${information.map { (key) -> Regex.escape(key) }.joinToString("|")})\b(?!-)"""
                  .toRegex()
                  .find(rewriter.tokenStream.getText(instructions).normalize())
      val awaited =
          when {
            amount?.type == FormParser.INT || amount?.type == FormParser.LITERAL ->
                amount.text.toAmount()
            matchResult != null -> {
              isInserted = true
              // For mailing address
              information.remove(matchResult.value)
            }
            else -> null
          }
      subtotal = ifConditionOrDefault(subtotal) { awaited }
    }
    for (instruction in instructions.instruction()) {
      val stat = instruction.stat()
      when {
        stat is FormParser.CannotBeContext -> {
          val conditions = listOf(condition(), instruction.condition())
          val bound =
              try {
                @Suppress("UNCHECKED_CAST") (visit(stat.condition()) as Deferred<Boolean>)
              } catch (e: IllegalExprException) {
                continue
              }
          launch {
            if (conditions.all()) {
              assert(!bound.await())
            }
          }
        }
        stat is FormParser.CannotExceedContext -> {
          val conditions = listOf(condition(), instruction.condition())
          val a = subtotal
          val b =
              try {
                @Suppress("UNCHECKED_CAST") (visit(stat.conditional()) as Deferred<BigDecimal?>?)
              } catch (e: IllegalExprException) {
                continue
              }
          launch {
            if (conditions.all()) {
              assert(a?.await() <= b?.await())
            }
          }
        }
        stat is FormParser.MaximumContext -> {
          val a = subtotal
          val b =
              try {
                @Suppress("UNCHECKED_CAST") (visit(stat.operand()) as Deferred<BigDecimal?>?)
              } catch (e: IllegalExprException) {
                continue
              }
          subtotal =
              instruction.ifConditionOrDefault(a) {
                (a?.await() as BigDecimal?)?.min(b?.await() ?: 0.toBigDecimal())
              }
        }
        stat is FormParser.UseColumnContext -> {
          val conditions = listOf(condition(), instruction.condition())
          useColumn[stat.id.text.normalize()] = async { conditions.all() }
        }
        stat != null &&
            listOf(condition(), instruction.condition()).all() !=
                stat is FormParser.CompleteLinesContext -> (visit(stat) as? Job)?.join()
      }
    }
    for (instruction in instructions.instruction()) {
      for (enter in instruction.enter()) {
        val default = subtotal
        val conditions = listOf(condition(), instruction.condition())
        val conditionalOperand = enter.conditional()
        val onClauses = enter.onClause()
        val bound =
            when {
              conditionalOperand != null &&
                  (instruction.OTHERWISE() != null ||
                      !instruction.conditionalClause().isEmpty() ||
                      conditionalOperand.operand().singleOrNull() !is FormParser.LiteralContext ||
                      !onClauses.isEmpty()) ->
                  try {
                    @Suppress("UNCHECKED_CAST") (visit(conditionalOperand) as Deferred<BigDecimal?>)
                  } catch (e: IllegalExprException) {
                    continue
                  }
              onClauses.size == 2 -> onClauses.first().ranges().single().getAll().single()
              else -> {
                onClauses.lastOrNull()?.assign(async { conditions.all() }, subtotal)
                continue
              }
            }
        subtotal =
            instruction.ifConditionOrDefault(default) {
              isInserted = true
              bound?.await()
            }
        onClauses.lastOrNull()?.assign(async { conditions.all() }, bound)
      }
      // For previousConditions
      instruction.condition()
    }
    if (ranges == null && expr !is FormParser.RestContext) {
      currentLines = null
    }
    currentLines?.also {
      for (line in it) {
        assigns.remove(line)?.forEach { (condition, consequent) ->
          val default = subtotal
          subtotal =
              ifConditionOrDefault(default) {
                if (condition.await()) {
                  isInserted = true
                  consequent?.await()
                } else default?.await()
              }
        }
      }
      for (line in it) {
        set(line)
      }
    }
    (ctx as? Either.SepContext)?.ctx?.TAG()?.also { set(it.text.normalize()) }
    for (i in instructions.sourceInterval) {
      val token = rewriter.tokenStream[i]
      if (token.type == FormParser.TAG) {
        set(token.text.normalize())
      }
    }
    val condition = condition()
    val bound =
        when ((expr as? FormParser.RestContext)?.op?.type) {
          FormParser.PLUS, FormParser.MINUS -> currentOperand
          else -> subtotal
        }
    launch {
      if (condition.await()) {
        val awaited = bound?.await()
        if (ranges != null || isInserted) {
          rewriterLock.lock()
          try {
            rewriter.replace(
                (amount ?: return@launch),
                when {
                  awaited == null -> "^"
                  isInserted -> "{+${awaited.formatAmount()}+}"
                  else -> awaited.formatAmount()
                })
          } finally {
            rewriterLock.unlock()
          }
        }
      }
    }
  }

  fun condition(): Deferred<Boolean> {
    val myCurrentLines = currentLines
    val myDontCompleteLines = dontCompleteLines
    val myColumnCondition = columnCondition
    return scope.async {
      (myCurrentLines == null ||
          myDontCompleteLines == null ||
          !myCurrentLines.any { myDontCompleteLines.contains(it) }) &&
          (myColumnCondition == null || myColumnCondition.await())
    }
  }

  fun ifConditionOrDefault(default: Deferred<Any?>?, block: suspend () -> Any?): Deferred<Any?>? {
    val myCurrentLines = currentLines
    val myDontCompleteLines = dontCompleteLines
    return if (myCurrentLines != null &&
        myDontCompleteLines != null &&
        myCurrentLines.any { myDontCompleteLines.contains(it) })
        default
    else {
      val myColumnCondition = columnCondition
      val oldValue = currentLines?.let { get(it.first(), currentPart, this) }
      scope.async {
        if (myColumnCondition == null || myColumnCondition.await()) block() else oldValue?.await()
      }
    }
  }

  fun FormParser.InstructionContext.condition(): Deferred<Boolean> {
    val isOtherwise = OTHERWISE() != null
    if (isOtherwise) {
      // assert(!previousConditions.isEmpty())
    }
    val myPreviousConditions = previousConditions.toList()
    val condition =
        conditionalClause().takeIf { !it.isEmpty() }?.single()?.let {
          @Suppress("UNCHECKED_CAST") (visit(it) as Deferred<Boolean>)
        }
    condition?.also { previousConditions += condition }
    return scope.async {
      (!isOtherwise || !myPreviousConditions.any()) && (condition == null || condition.await())
    }
  }

  fun FormParser.InstructionContext.ifConditionOrDefault(
      default: Deferred<Any?>?,
      block: suspend () -> Any?
  ): Deferred<Any?>? {
    val bound = condition()
    return this@EvalVisitor.ifConditionOrDefault(default) {
      if (bound.await()) block() else default?.await()
    }
  }

  fun FormParser.OnClauseContext.assign(condition: Deferred<Boolean>, consequent: Deferred<Any?>?) {
    for (ranges in ranges()) {
      for ((line, part, form) in
          try {
            @Suppress("UNCHECKED_CAST") (visit(ranges) as Ranges)
          } catch (e: IllegalExprException) {
            continue
          }) {
        assert(part == null)
        (form ?: this@EvalVisitor).assigns.getOrPut(line) { mutableListOf() } +=
            condition to consequent
      }
    }
  }

  override fun visitAggregate(ctx: FormParser.AggregateContext): Deferred<Any?>? {
    val rest = ctx.rest()
    return when {
      !ctx.ops.isEmpty() -> {
        assert(ctx.ADD() == null)
        assert(rest == null)
        ctx.ranges().getAll().conditional(ctx.ops)
      }
      rest != null -> {
        ctx.ADD()?.also { assert(rest.op.type == FormParser.PLUS) }
        subtotal = ctx.ranges().getAll().single()
        @Suppress("UNCHECKED_CAST") (visit(rest) as Deferred<BigDecimal?>)
      }
      ctx.ADD() != null || ctx.FROM() != null -> ctx.ranges().getAll().sum()
      else -> {
        val (line, part, form) =
            @Suppress("UNCHECKED_CAST") (visit(ctx.ranges()) as Ranges).singleOrNull()
                ?: throw IllegalExprException()
        part ?: form ?: throw IllegalExprException()
        (form ?: this).get(line, part, this)
      }
    }
  }

  override fun visitMultiply(ctx: FormParser.MultiplyContext): Deferred<BigDecimal?> {
    val a = ctx.a.getAll().single()
    val b = @Suppress("UNCHECKED_CAST") (visit(ctx.b) as Deferred<BigDecimal?>)
    return scope.async {
      (a?.await() as BigDecimal? ?: return@async null) * (b.await() ?: return@async null)
    }
  }

  override fun visitEnterLiteral(ctx: FormParser.EnterLiteralContext): Deferred<BigDecimal> =
      CompletableDeferred((ctx.INT() ?: ctx.LITERAL()).text.toAmount())

  override fun visitSpousesNetIncome(ctx: FormParser.SpousesNetIncomeContext) =
      spousesReturn?.get("this is your net income", null, this)

  override fun visitUnusedRrspContributions(
      ctx: FormParser.UnusedRrspContributionsContext
  ): Deferred<BigDecimal?>? {
    val previousYear = year - 1
    val basename = "5000-s7-%02de.txt".format(previousYear % 100)
    return (@Suppress("UNCHECKED_CAST")
    ((scope.evalForm(File("$previousYear/${directory.name}/$basename"))
            ?: throw IllegalExprException()).get(
        "unused rrsp contributions available to carry forward to a future year", null, this) as
        Deferred<BigDecimal?>?))
  }

  override fun visitTheirInformation(ctx: FormParser.TheirInformationContext) =
      theirReturn?.props?.get(
              when (ctx.THEIR_INFORMATION().text.normalize()) {
                "first name" -> "first name and initial"
                "sin" -> "social insurance number"
                else -> throw NotImplementedError()
              })
          ?.let { CompletableDeferred(it) }

  override fun visitRest(ctx: FormParser.RestContext): Deferred<BigDecimal?> {
    val a = subtotal
    val b = @Suppress("UNCHECKED_CAST") (visit(ctx.conditional()) as Deferred<BigDecimal?>)
    currentOperand = b
    return scope.async {
      when (ctx.op.type) {
        FormParser.PLUS -> {
          a?.await() ?: b.await() ?: return@async null
          (a?.await() as BigDecimal? ?: 0.toBigDecimal()) + (b.await() ?: 0.toBigDecimal())
        }
        FormParser.MINUS -> {
          a?.await() ?: b.await() ?: return@async null
          (a?.await() as BigDecimal? ?: 0.toBigDecimal()) - (b.await() ?: 0.toBigDecimal())
        }
        FormParser.MULTIPLIED -> (a?.await() as BigDecimal?
                ?: return@async null) * (b.await() ?: return@async null)
        FormParser.DIVIDED -> (a?.await() as BigDecimal?
                ?: return@async null) / (b.await() ?: return@async null)
        else -> throw NotImplementedError()
      }
    }
  }

  override fun visitFocusSpouse(ctx: FormParser.FocusSpouseContext) {
    theirReturn = spousesReturn
  }

  override fun visitFocusDependant(ctx: FormParser.FocusDependantContext) {
    theirReturn = null
  }

  override fun visitCompleteForm(ctx: FormParser.CompleteFormContext): Job? {
    val form = visit(ctx.ref()) as EvalVisitor?
    completeFormsLock.lock()
    try {
      return if (form == null || form == yourReturn || form.dependsOn(this)) null
      else {
        completeForms += form
        form.job
      }
    } finally {
      completeFormsLock.unlock()
    }
  }

  override fun visitCompleteLines(ctx: FormParser.CompleteLinesContext) {
    dontCompleteLines =
        @Suppress("UNCHECKED_CAST")
        (visit(ctx.ranges()) as Ranges).map { (line, part, form) ->
          assert(part == null)
          assert(form == null)
          line
        }
  }

  override fun visitConditionalClause(ctx: FormParser.ConditionalClauseContext): Deferred<Boolean> {
    ctx.operand()?.also { subtotal = visit(it) as Deferred<Any?>? }
    val bound = ctx.condition().map { @Suppress("UNCHECKED_CAST") (visit(it) as Deferred<Boolean>) }
    return scope.async { bound.all() }
  }

  override fun visitPositive(ctx: FormParser.PositiveContext): Deferred<Boolean> {
    val bound = subtotal
    return scope.async { bound?.await() > 0.toBigDecimal() }
  }

  override fun visitNegative(ctx: FormParser.NegativeContext): Deferred<Boolean> {
    val bound = subtotal
    return scope.async { bound?.await() < 0.toBigDecimal() }
  }

  override fun visitComparison(ctx: FormParser.ComparisonContext): Deferred<Boolean> {
    val a = subtotal
    val b =
        @Suppress("UNCHECKED_CAST")
        (visit(ctx.conditional() ?: ctx.operand()) as Deferred<BigDecimal?>?)
    return scope.async {
      when (ctx.op.type) {
        FormParser.MORE_THAN -> a?.await() > b?.await()
        FormParser.LESS_THAN -> a?.await() < b?.await()
        FormParser.OR_MORE -> a?.await() >= b?.await()
        FormParser.OR_LESS -> a?.await() <= b?.await()
        else -> throw NotImplementedError()
      }
    }
  }

  override fun visitEquals(ctx: FormParser.EqualsContext): Deferred<Boolean> {
    val a = subtotal
    val b = (ctx.INT() ?: ctx.LITERAL()).text.toAmount()
    return scope.async {
      val awaited = a?.await()
      awaited is BigDecimal? && awaited ?: 0.toBigDecimal() == b
    }
  }

  override fun visitNot(ctx: FormParser.NotContext): Deferred<Boolean> {
    val bound = @Suppress("UNCHECKED_CAST") (visit(ctx.condition()) as Deferred<Boolean>)
    return scope.async { !bound.await() }
  }

  override fun visitConditional(ctx: FormParser.ConditionalContext): Deferred<BigDecimal?> {
    val operands = ctx.operand()
    val lines = operands.singleOrNull() as? FormParser.LinesContext
    return (if (!ctx.ops.isEmpty() && lines != null && lines.rest() == null) lines.ranges().getAll()
        else operands.map { @Suppress("UNCHECKED_CAST") (visit(it) as Deferred<BigDecimal?>?) })
        .conditional(ctx.ops)
  }

  override fun visitLines(ctx: FormParser.LinesContext): Deferred<Any?>? {
    val rest = ctx.rest()
    return if (rest == null) ctx.ranges().getAll().single()
    else {
      val savedSubtotal = subtotal
      subtotal = ctx.ranges().getAll().single()
      @Suppress("UNCHECKED_CAST")
      (visit(rest) as Deferred<BigDecimal?>).also { subtotal = savedSubtotal }
    }
  }

  override fun visitLiteral(ctx: FormParser.LiteralContext): Deferred<BigDecimal?> {
    val a = (ctx.INT() ?: ctx.LITERAL()).text.toAmount()
    val b =
        @Suppress("UNCHECKED_CAST")
        (visit(ctx.operand() ?: return CompletableDeferred(a)) as Deferred<BigDecimal?>?)
    return scope.async { a * (b?.await() ?: return@async null) }
  }

  override fun visitYourNetIncome(ctx: FormParser.YourNetIncomeContext) =
      yourReturn.get("this is your net income", null, this)

  override fun visitTheirNetIncome(ctx: FormParser.TheirNetIncomeContext) =
      theirReturn?.get("this is your net income", null, this)

  override fun visitYourTotalContributions(ctx: FormParser.YourTotalContributionsContext) =
      jurisdiction.get("political contributions")

  override fun visitAdd(ctx: FormParser.AddContext) = ctx.ranges().getAll().sum()

  override fun visitAsAPositive(ctx: FormParser.AsAPositiveContext): Deferred<BigDecimal?> {
    val bound = subtotal
    return scope.async { (bound?.await() as BigDecimal?)?.abs() }
  }

  override fun visitTag(ctx: FormParser.TagContext): Deferred<Any?>? {
    val line = ctx.TAG().text.normalize()
    return (internal.asIterable().lastOrNull { (key) -> key.first == line }
            ?: throw IllegalExprException())
        .value
  }

  fun dependsOn(other: EvalVisitor): Boolean =
      this == other || completeForms.any { it.dependsOn(other) }

  fun FormParser.RangesContext.getAll(): List<Deferred<Any?>?> =
      @Suppress("UNCHECKED_CAST")
      (visit(this) as Ranges).map { (line, part, form) ->
        (form
                ?: return@map internal[
                    line to
                        (part
                            ?: return@map (internal.asIterable().lastOrNull { (key) ->
                                  key.first == line
                                }
                                    ?: return@map if (line.toIntOrNull() ?: 0 < 100) null
                                    else {
                                      listOf(
                                              yourReturn,
                                              run {
                                                val basename =
                                                    "5000-s1-%02de.txt".format(year % 100)
                                                scope.evalForm(File(directory, basename))
                                              },
                                              run {
                                                val basename = "5010-c-%02de.txt".format(year % 100)
                                                scope.evalForm(File(directory, basename))
                                              },
                                          )
                                          .get(line, part)
                                    })
                                .value)])
            .get(line, part, this@EvalVisitor)
      }

  fun Iterable<Deferred<Any?>?>.sum(): Deferred<BigDecimal?> =
      scope.async {
        mapNotNull { it?.await() as BigDecimal? }.takeIf { !it.isEmpty() }?.sumOf { it }
      }

  fun List<Deferred<Any?>?>.conditional(ops: List<Token>): Deferred<BigDecimal?> {
    if (!ops.isEmpty() && size == 1) {
      throw IllegalExprException()
    }
    return scope.async {
      map { it?.await() as BigDecimal? }.reduce { a, b ->
        a ?: b ?: return@reduce null
        when (ops.single().type) {
          FormParser.WHICHEVER_IS_MORE -> (a ?: 0.toBigDecimal()).max(b ?: 0.toBigDecimal())
          FormParser.WHICHEVER_IS_LESS -> (a ?: 0.toBigDecimal()).min(b ?: 0.toBigDecimal())
          else -> throw NotImplementedError()
        }
      }
    }
  }

  fun Iterable<EvalVisitor?>.get(line: String, part: String? = null): Deferred<Any?> {
    val bound = mapNotNull { it?.get(line, part, this@EvalVisitor) }
    return scope.async {
      @OptIn(ExperimentalCoroutinesApi::class)
      bound.map { @OptIn(FlowPreview::class) it::await.asFlow() }.merge().firstOrNull { it != null }
    }
  }

  fun get(line: String, part: String?, caller: EvalVisitor): Deferred<Any?>? {
    return if (caller == this)
        internal[
            line to
                (part
                    ?: return internal
                        .asIterable()
                        .lastOrNull { (key) -> key.first == line }
                        ?.value)]
    else {
      // assert(part == null)
      externalLock.lock()
      try {
        external.getOrPut(line) {
          if (!isActive) {
            return null
          }
          CompletableDeferred()
        }
      } finally {
        externalLock.unlock()
      }
    }
  }

  fun set(line: String) {
    internal[line to currentPart] = subtotal
    val source = subtotal
    externalLock.lock()
    val target =
        try {
          external.getOrPut(line) { CompletableDeferred() }
        } finally {
          externalLock.unlock()
        }
    scope.launch { target.complete(source?.await()) }
  }

  override fun visitRanges(ctx: FormParser.RangesContext): Ranges {
    var currentPart: String? = null
    var currentForm: EvalVisitor? = null
    return ctx.range().asReversed().flatMap {
      val (lines, part, form) = @Suppress("UNCHECKED_CAST") (visit(it) as Range)
      lines.map {
        part?.also { currentPart = part }
        form?.also { currentForm = form }
        Triple(it, currentPart, currentForm)
      }
    }
  }

  override fun visitRange(ctx: FormParser.RangeContext): Range {
    val start = ctx.start.text
    val lines =
        if (ctx.end == null) listOf(start.normalize())
        else
            ((start.toIntOrNull()
                    ?: throw IllegalExprException())..(ctx.end.text.toIntOrNull()
                        ?: throw IllegalExprException()))
                .map { it.toString() }
    val ofClause = ctx.ofClause()
    val (part, form) =
        if (ofClause == null) null to null
        else @Suppress("UNCHECKED_CAST") (visit(ofClause) as Pair<String?, EvalVisitor?>)
    return Range(lines, part, form)
  }

  override fun visitOfClause(ctx: FormParser.OfClauseContext): Pair<String?, EvalVisitor?> =
      ctx.part()?.id?.text?.normalize() to
          ctx.ref()?.let { visit(it) as EvalVisitor? ?: throw IllegalExprException() }

  override fun visitRef(ctx: FormParser.RefContext): EvalVisitor? {
    val key = ctx.REF().text.normalize()
    val basename =
        when (key) {
          "the next page", "the previous page" -> return this
          "your return" -> return yourReturn
          "their return", "his or her return" -> return theirReturn
          "form 428", "form bc428" -> "5010-c-%02de.txt".format(year % 100)
          "schedule 1-a" -> "5010-s1a-%02de.txt".format(year % 100)
          "worksheet for the return", "federal worksheet" -> "5000-d1-%02de.txt".format(year % 100)
          "worksheet bc428", "provincial worksheet" -> "5010-d-%02de.txt".format(year % 100)
          "worksheet for schedule 1" -> "5000-d2-%02de.txt".format(year % 100)
          "form 479" -> "5010-tc-%02de.txt".format(year % 100)
          "form cpt20",
          "form cpt30",
          "form gst370",
          "form rc381",
          "form rc383",
          "form t1-m",
          "form t1032",
          "form t1135",
          "form t1172",
          "form t1206",
          "form t1212",
          "form t1223",
          "form t1229",
          "form t1231",
          "form t1a",
          "form t2017",
          "form t2036",
          "form t2038",
          "form t2091",
          "form t2202",
          "form t2202a",
          "form t2209",
          "form t2222",
          "form t626",
          "form t657",
          "form t691",
          "form t778",
          "form t90",
          "form t929" -> return null
          else ->
              when {
                key.startsWith("this ") -> return this
                listOf("their ", "his or her ").any { key.startsWith(it) } -> return null
                else -> {
                  val matchResult = """schedule \w+\(s(.*?)\)""".toRegex().matchEntire(key)
                  when {
                    matchResult != null -> {
                      val (schedule) = matchResult.destructured
                      "5010-s%d-%02de.txt".format(schedule.toInt(), year % 100)
                    }
                    key.startsWith("schedule ") -> {
                      val schedule = key.removePrefix("schedule ")
                      "5000-s%d-%02de.txt".format(schedule.toInt(), year % 100)
                    }
                    else -> throw NotImplementedError()
                  }
                }
              }
        }
    return scope.evalForm(File(directory, basename))
  }
}

fun CoroutineScope.evalForm(file: File): EvalVisitor? {
  formsLock.lock()
  try {
    return forms.getOrElse(file) { if (!file.exists()) null else EvalVisitor(file, this) }
  } finally {
    formsLock.unlock()
  }
}

typealias Forms = Map<File, EvalVisitor>

typealias Overrides = Map<File, Map<String, Any?>>

fun EvalVisitor.unusedRrspContributions(): BigDecimal =
    @OptIn(ExperimentalCoroutinesApi::class)
    get("unused rrsp contributions available to carry forward to a future year", null, this)
        ?.getCompleted() as
        BigDecimal?
        ?: 0.toBigDecimal()

fun EvalVisitor.federalTax(): BigDecimal =
    @OptIn(ExperimentalCoroutinesApi::class) get("net federal tax", null, this)?.getCompleted() as
        BigDecimal?
        ?: 0.toBigDecimal()

fun EvalVisitor.provincialTax(): BigDecimal =
    @OptIn(ExperimentalCoroutinesApi::class)
    get("provincial or territorial tax", null, this)?.getCompleted() as
        BigDecimal?
        ?: 0.toBigDecimal()

fun EvalVisitor.balance(): BigDecimal =
    @OptIn(ExperimentalCoroutinesApi::class)
    get("this is your refund or balance owing", null, this)?.getCompleted() as
        BigDecimal?
        ?: 0.toBigDecimal()

// https://youtrack.jetbrains.com/issue/KT-36932#focus=Comments-27-4839752.0-0
infix fun ClosedRange<BigDecimal>.step(step: BigDecimal) =
    List(((endInclusive + step - start) / step).toInt()) { start + it.toBigDecimal() * step }

fun <T> List<T>.combinations(k: Int): List<List<T>> =
    when {
      k == 0 -> listOf(listOf())
      size == 0 -> listOf()
      else -> drop(1).combinations(k - 1).map { take(1) + it } + drop(1).combinations(k)
    }

fun Forms.deductRrspContributions() {
  for ((file, form) in this) {
    val unusedRrspContributions = form.unusedRrspContributions()
    if (unusedRrspContributions > 0.toBigDecimal() &&
        form.yourReturn.federalTax() > 0.toBigDecimal() &&
        form.yourReturn.provincialTax() > 0.toBigDecimal()) {
      (0.01.toBigDecimal()..unusedRrspContributions step 0.01.toBigDecimal()).binarySearch {
        overrides.getOrPut(file) { mutableMapOf() }["contributions you are deducting"] = it
        forms -= keys
        val newForm = runBlocking(Dispatchers.Default) { evalForm(file)!! }
        if (newForm.yourReturn.federalTax() > 0.toBigDecimal() &&
            newForm.yourReturn.provincialTax() > 0.toBigDecimal())
            -1
        else 1
      }
      overrides[file]!! -= "contributions you are deducting"
      forms.entries.removeAll { (otherFile, otherForm) ->
        otherForm.year > form.year &&
            otherForm.directory.name == form.directory.name &&
            """5000-s7-.*e\.txt""".toRegex().matches(otherFile.name)
      }
    }
  }
}

fun EvalVisitor.choices(): List<Overrides> {
  val choices = mutableListOf<Overrides>()
  spousesReturn?.also {
    choices +=
        mapOf(
            directory to mapOf("medical expenses for self" to spousesReturn.medicalExpensesForSelf),
            spousesReturn.directory to mapOf("medical expenses for self" to medicalExpensesForSelf),
        )
  }
  for (form in listOf(this, spousesReturn)) {
    form?.also {
      (form.props["tax-exempt income for emergency services volunteers"] as String?)?.also {
        choices +=
            mapOf(
                form.directory to
                    mapOf(
                        "employment income" to form.props.employmentIncome() + it.toAmount(),
                        "tax-exempt income for emergency services volunteers" to null,
                        "volunteer firefighters' amount" to 3000.toBigDecimal(),
                    ),
            )
      }
    }
  }
  return choices
}

suspend fun Forms.refreshed(): Forms = mapValues { (file) -> coroutineScope { evalForm(file)!! } }

suspend fun Forms.minimizeBalance(choices: List<Overrides>): Forms {
  refreshed().deductRrspContributions()
  return (1..choices.size).flatMap { choices.combinations(it) }.fold(refreshed()) {
      bestForms,
      combination ->
    for (choice in combination) {
      for ((file, fileOverrides) in choice) {
        overrides.getOrPut(file) { mutableMapOf() } += fileOverrides
      }
    }
    forms -= keys
    refreshed().deductRrspContributions()
    val newForms = refreshed()
    for (choice in combination) {
      for ((file, fileOverrides) in choice) {
        overrides[file]!! -= fileOverrides.keys
      }
    }
    val (_, newForm) = newForms.asIterable().first()
    val (_, bestForm) = bestForms.asIterable().first()
    if (newForm.yourReturn.balance() + newForm.spousesReturn!!.balance() <
        bestForm.yourReturn.balance() + bestForm.spousesReturn!!.balance())
        newForms
    else bestForms
  }
}

suspend fun main(args: Array<String>) {
  coroutineScope {
    for (pathname in args) {
      evalForm(File(pathname))
    }
  }
  for ((_, group) in
      forms.asIterable().groupBy { (_, form) -> form.year }.asIterable().sortedBy { (year) ->
        year
      }) {
    val (_, form) = group.first()
    forms += group.map { it.toPair() }.toMap().minimizeBalance(form.choices())
  }
  for ((file, form) in forms) {
    file.writeText(form.rewriter.text)
  }
}
