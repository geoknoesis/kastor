package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.stronglyConnectedComponents

/** Three-valued conformance of [value] to [ref], with [negative] recording non-monotone recursive reads. */
internal fun NativeShaclValidator.conformance(value: RdfTerm, ref: RdfResource, ctx: NativeValidationContext, state: DepthState, negative: Boolean = false): Conformance {
    // Amortised: the shape evaluations this question leads to consult the budget themselves.
    ctx.budget.tick()
    val compiled = ctx.compiled
    val propertyShape = compiled.referencedPropertyShapes[ref]
    val nodeShape = if (propertyShape == null) compiled.referencedNodeShapes[ref] else null
    if (propertyShape == null && nodeShape == null) return Conformance.CONFORMS
    if (propertyShape?.deactivated == true || nodeShape?.deactivated == true) return Conformance.CONFORMS
    val key = AtomKey(TermKey(value), ref)
    ctx.memo[key]?.let { return it }
    val component = compiled.recursiveComponents[ref]
    if (component != null) {
        val active = ctx.solver
        if (active != null && active.component == component) {
            val answer = readInSolver(active, ctx, key, value, ref, negative)
            ctx.recordingSink?.reads?.add(SolverRead(value, ref, answer))
            return answer
        }
        return solve(key, value, ref, component, ctx, state)
    }
    val next = state.nested()
    if (next.depth > config.maxRecursionDepth) {
        throw ShaclValidationException(
            "SHACL shape nesting exceeded ValidationConfig.maxRecursionDepth=${config.maxRecursionDepth} at shape ${ref.displayId()}",
        )
    }
    val result = evaluateConformance(value, ref, ctx, next)
    ctx.memo[key] = result
    return result
}

/** Evaluates every constraint of [ref] for [value] in the (non-report) mode of [state]. */
internal fun NativeShaclValidator.evaluateConformance(value: RdfTerm, ref: RdfResource, ctx: NativeValidationContext, state: DepthState): Conformance {
    val sink = Sink(state.mode)
    // A nested evaluation is not part of a recorded one: its solver reads (of another component) are its own.
    val outer = ctx.recordingSink
    ctx.recordingSink = null
    try {
        evaluateInto(value, ref, ctx, state, sink)
    } finally {
        ctx.recordingSink = outer
    }
    val answer = sink.conformance()
    rememberCause(AtomKey(TermKey(value), ref), sink, answer, ctx)
    return answer
}

internal fun NativeShaclValidator.evaluateInto(value: RdfTerm, ref: RdfResource, ctx: NativeValidationContext, state: DepthState, sink: Sink) {
    // The node expressions this evaluation reaches (also the function bodies its SPARQL queries call) validate
    // deeper than [state] (NativeValidationContext.expressionShapeDepth).
    val savedDepth = ctx.expressionShapeDepth
    ctx.expressionShapeDepth = state.depth
    try {
        val propertyShape = ctx.compiled.referencedPropertyShapes[ref]
        if (propertyShape != null) {
            validatePropertyShape(value, propertyShape, ctx, state, sink)
        } else {
            ctx.compiled.referencedNodeShapes[ref]?.let { validateNodeShape(value, it, ctx, state, sink) }
        }
    } finally {
        ctx.expressionShapeDepth = savedDepth
    }
}

/**
 * One evaluation of the solver question [key] = ([node], [shape]) at nesting [depth]: [exhaustive] in the
 * recording pass, stopping at the first failure otherwise. When the question is a focus node whose results will
 * be reported ([NativeValidationContext.records]), the evaluation is a [Mode.RECORD] one (exhaustive in either pass,
 * which reads exactly the questions its recording pass registered) and replaces the question's [Recording].
 */
internal fun NativeShaclValidator.evaluateQuestion(key: AtomKey, node: RdfTerm, shape: RdfResource, ctx: NativeValidationContext, depth: Int, exhaustive: Boolean): Conformance {
    val record = ctx.records(node, shape)
    val mode = if (record) Mode.RECORD else if (exhaustive) Mode.EXHAUSTIVE else Mode.CONFORMS
    val sink = Sink(mode)
    if (record) {
        sink.reads = ArrayList()
        sink.units = ArrayList()
    }
    val outer = ctx.recordingSink
    ctx.recordingSink = if (record) sink else null
    try {
        evaluateInto(node, shape, ctx, DepthState(depth, mode), sink)
    } finally {
        ctx.recordingSink = outer
    }
    val answer = sink.conformance()
    rememberCause(key, sink, answer, ctx)
    // Without a recording, an earlier one stays: any recorded evaluation of the question will do (recordedResults).
    if (record) ctx.keepRecording(key, Recording(sink.results, sink.reads!!, sink.units!!))
    return answer
}

/**
 * The results of a focus node from the [recording] of the evaluation by which the solver settled its question,
 * exactly as a report evaluation would produce them, or null when they cannot be derived from it.
 *
 * A recorded evaluation is a report evaluation whose solver reads were answered by the solver at the time: with
 * "conforms" in the recording pass, with the value a question had then (or "undefined" while it was unsettled)
 * afterwards. Everything else it reads is final when it is read (the memo of settled answers, other components,
 * the data). So its results are the final ones wherever the answers it was given are the final answers. The reads
 * are grouped by the [ResultUnit] that made them. A unit whose answers are all definite and equal to the final
 * ones contributes its recorded results; any other unit is evaluated again, now that every answer is final —
 * that is one constraint on the value nodes already computed, not the shape. An undefined answer always replays
 * its unit, because the result of an undefined answer states its cause, which is only known in the end. A read
 * outside every unit (the shape-based node expressions of `sh:values` / `sh:defaultValue`, which are evaluated before
 * the constraints of a property shape) cannot be replayed: null, and the caller evaluates the focus node again.
 */
internal fun NativeShaclValidator.recordedResults(recording: Recording, ctx: NativeValidationContext): List<ValidationViolation>? {
    val reads = recording.reads
    if (reads.isEmpty()) return recording.results
    val top = DepthState(0, Mode.CONFORMS)
    var changedAny = false
    val changed = BooleanArray(reads.size) { i ->
        val read = reads[i]
        // A question the solve did not explore (the reader failed whatever its answer) is solved now.
        val isFinal = read.answer != Conformance.UNDEFINED && conformance(read.node, read.shape, ctx, top) == read.answer
        if (!isFinal) changedAny = true
        !isFinal
    }
    if (!changedAny) return recording.results
    val out = ArrayList<ValidationViolation>(recording.results.size)
    var resultAt = 0
    var readAt = 0
    for (unit in recording.units) {
        for (i in readAt until unit.readsFrom) if (changed[i]) return null
        for (i in resultAt until unit.resultsFrom) out.add(recording.results[i])
        if ((unit.readsFrom until unit.readsTo).any { changed[it] }) {
            val sink = Sink(Mode.REPORT)
            unit.replay(sink)
            out.addAll(sink.results)
        } else {
            for (i in unit.resultsFrom until unit.resultsTo) out.add(recording.results[i])
        }
        resultAt = unit.resultsTo
        readAt = unit.readsTo
    }
    for (i in readAt until reads.size) if (changed[i]) return null
    for (i in resultAt until recording.results.size) out.add(recording.results[i])
    return out
}

/**
 * Runs [body], a self-contained part of a shape evaluation (see [ResultUnit]), on this sink. In a recorded
 * evaluation, a part that read the recursion solver is registered with the results it produced and the reads it
 * made, so that it can be evaluated again alone.
 */
internal inline fun Sink.unit(crossinline body: (Sink) -> Unit) {
    val log = reads
    if (log == null) {
        body(this)
        return
    }
    val resultsFrom = results.size
    val readsFrom = log.size
    body(this)
    if (log.size > readsFrom) units!!.add(ResultUnit(resultsFrom, results.size, readsFrom, log.size) { sink -> body(sink) })
}

/**
 * Records why the question [key] was just answered [answer] by the evaluation in [sink]: when the answer is
 * undefined and an undecided pattern is among the causes, the pattern and whether undefined recursion is a cause
 * too. Any other answer clears the entry of an earlier evaluation (a solver pass under other assumptions), so a
 * cause never outlives the answer it explains.
 */
internal fun NativeShaclValidator.rememberCause(key: AtomKey, sink: Sink, answer: Conformance, ctx: NativeValidationContext) {
    val pattern = sink.cause
    if (answer == Conformance.UNDEFINED && pattern != null) {
        ctx.undefined[key] = UndefinedCause(sink.recursion, pattern)
    } else if (ctx.undefined.isNotEmpty()) {
        ctx.undefined.remove(key)
    }
}

/**
 * Whether the undefined answer of ([node], [shape]) should be reported instead of the one of [current] (null:
 * none yet) by a constraint that depends on several undefined answers. Undefined recursion is preferred to an
 * undecided pattern, and the first one in evaluation order among equals, so the reported status does not depend
 * on the order of the operands.
 */
internal fun NativeShaclValidator.prefersUndefined(ctx: NativeValidationContext, current: Pair<RdfTerm, RdfResource>?, node: RdfTerm, shape: RdfResource): Boolean {
    if (current == null) return true
    if (ctx.undefined.isEmpty()) return false
    fun byRecursion(n: RdfTerm, s: RdfResource) = ctx.undefined[AtomKey(TermKey(n), s)]?.recursion ?: true
    return !byRecursion(current.first, current.second) && byRecursion(node, shape)
}

internal fun NativeShaclValidator.readInSolver(solver: RecursionSolver, ctx: NativeValidationContext, key: AtomKey, value: RdfTerm, ref: RdfResource, negative: Boolean): Conformance {
    solver.recording?.let { reads ->
        if (ctx.dropRecordedRead?.invoke(value, ref) != true) reads.add(Dependency(key, value, ref, negative))
        return Conformance.CONFORMS
    }
    val atom = solver.atoms[key]
    if (atom == null || (!atom.resolved && !atom.inGroup)) {
        // Fail safe: a question the recording did not register, or a dependency it did not record (so the
        // question may not be settled yet), is never answered by default; the solve restarts with it recorded.
        solver.restart = true
        if (atom == null) {
            solver.atoms[key] = Atom(value, ref)
            solver.discovered++
        } else {
            val reader = solver.evaluating
            if (reader != null && reader.missedReads.none { it.key == key && it.negative == negative }) {
                reader.missedReads.add(Dependency(key, value, ref, negative))
                solver.discovered++
            }
        }
        return Conformance.UNDEFINED
    }
    return if (atom.readUndefined) Conformance.UNDEFINED else atom.value
}

/** Answers the question [rootKey] on a shape of the recursive [component]; see the class KDoc. */
internal fun NativeShaclValidator.solve(rootKey: AtomKey, rootNode: RdfTerm, rootShape: RdfResource, component: Int, ctx: NativeValidationContext, state: DepthState): Conformance {
    val solver = RecursionSolver(component)
    val savedSolver = ctx.solver
    try {
        ctx.solver = solver
        solver.atoms[rootKey] = Atom(rootNode, rootShape)
        var toRecord: Collection<AtomKey> = listOf(rootKey)
        while (true) {
            recordDependencies(solver, toRecord, ctx, state)
            evaluateGroups(solver, rootKey, ctx, state)
            if (!solver.restart) break
            if (solver.discovered == 0) {
                // Unreachable: every restart registers a new question or a new missed dependency.
                throw ShaclValidationException(
                    "Internal error in the SHACL recursion solver: an unsettled question was read again after its " +
                        "dependency had been recorded (shape ${rootShape.displayId()}); please report this shapes graph",
                )
            }
            // The recorded dependency graph was incomplete: record every question again and re-evaluate.
            ctx.counters.solverRestarts++
            solver.restart = false
            solver.discovered = 0
            for (atom in solver.atoms.values) {
                atom.resolved = false
                atom.inGroup = false
                atom.readUndefined = false
                atom.refinedAt = -1L
                atom.dependencies = emptyList()
            }
            toRecord = solver.atoms.keys.toList()
        }
        for ((key, atom) in solver.atoms) {
            if (!atom.resolved) continue
            ctx.memo[key] = atom.value
            // A conforming focus node has no results to report.
            if (atom.value == Conformance.CONFORMS && ctx.recordings.isNotEmpty()) ctx.keepRecording(key, null)
        }
        return solver.atoms.getValue(rootKey).value
    } finally {
        ctx.solver = savedSolver
    }
}

/** Step 1: evaluates each question once with recursive reads answered "conforms", recording those reads. */
internal fun NativeShaclValidator.recordDependencies(solver: RecursionSolver, start: Collection<AtomKey>, ctx: NativeValidationContext, state: DepthState) {
    val recorded = HashSet<AtomKey>()
    val pending = ArrayDeque(start)
    while (pending.isNotEmpty()) {
        ctx.checkDeadline()
        val key = pending.removeLast()
        if (!recorded.add(key)) continue
        val atom = solver.atoms.getValue(key)
        val reads = ArrayList<Dependency>()
        val saved = solver.recording
        solver.recording = reads
        val result =
            try {
                evaluateQuestion(key, atom.node, atom.shape, ctx, state.depth, exhaustive = true)
            } finally {
                solver.recording = saved
            }
        atom.optimistic = result
        reads.addAll(atom.missedReads)
        if (reads.isEmpty() || (result == Conformance.FAILS && reads.none { it.negative })) {
            // No recursive read, or failing although every (positive) read conformed: decided by monotonicity.
            solver.settle(atom, result)
            continue
        }
        atom.dependencies = reads
        for (dependency in reads) {
            if (dependency.key in recorded) continue
            if (dependency.key !in solver.atoms) solver.atoms[dependency.key] = Atom(dependency.node, dependency.shape)
            pending.add(dependency.key)
        }
    }
}

/** Step 2: evaluates groups of mutually dependent questions, dependencies first. */
internal fun NativeShaclValidator.evaluateGroups(solver: RecursionSolver, rootKey: AtomKey, ctx: NativeValidationContext, state: DepthState) {
    val evaluationState = DepthState(state.depth, Mode.CONFORMS)
    fun successors(key: AtomKey, within: Set<AtomKey>?): List<AtomKey> =
        solver.atoms.getValue(key).dependencies.mapNotNull { d ->
            d.key.takeIf { k -> !solver.atoms.getValue(k).resolved && (within == null || k in within) }
        }
    val work = ArrayDeque(stronglyConnectedComponents(listOf(rootKey), ctx.budget) { successors(it, null) })
    while (work.isNotEmpty()) {
        ctx.checkDeadline()
        val members = work.removeFirst().filter { !solver.atoms.getValue(it).resolved }
        if (members.isEmpty()) continue
        val memberSet = members.toHashSet()
        val atoms = members.map { solver.atoms.getValue(it) }
        if (atoms.none { atom -> atom.dependencies.any { it.negative && it.key in memberSet } }) {
            greatestFixpoint(solver, members, memberSet, ctx, evaluationState)
            if (solver.restart) return
            continue
        }
        if (!refine(solver, members, memberSet, atoms, ctx, evaluationState)) {
            if (solver.restart) return
            // Every remaining question lies on a cycle through a negative dependency: undefined by recursion,
            // whatever else (an undecided pattern) its last evaluation found.
            for ((index, atom) in atoms.withIndex()) {
                solver.settle(atom, Conformance.UNDEFINED)
                if (ctx.undefined.isNotEmpty()) ctx.undefined[members[index]]?.let { ctx.undefined[members[index]] = UndefinedCause(true, it.pattern) }
            }
            continue
        }
        val rest = members.filter { !solver.atoms.getValue(it).resolved }
        val restSet = rest.toHashSet()
        val split = stronglyConnectedComponents(rest, ctx.budget) { successors(it, restSet) }
        for (group in split.asReversed()) work.addFirst(group)
    }
}

/**
 * Settles the questions of a group with negative dependencies whose answer does not depend on the group's
 * unsettled answers (every read of an unsettled member answers undefined; by Kleene monotonicity a definite answer
 * stays valid whatever those members turn out to be). Incremental: a question is evaluated again only when one of
 * its recorded dependencies was settled to a definite answer since its last evaluation, so a group costs its
 * members plus its dependency edges rather than one pass over every member per settled question.
 * Returns whether any question was settled (false also on a solver restart).
 */
internal fun NativeShaclValidator.refine(
    solver: RecursionSolver,
    members: List<AtomKey>,
    memberSet: Set<AtomKey>,
    atoms: List<Atom>,
    ctx: NativeValidationContext,
    state: DepthState,
): Boolean {
    fun changedSinceRefined(atom: Atom): Boolean =
        atom.refinedAt < 0 || atom.dependencies.any { d ->
            // A dependency settled to undefined changes nothing: it was read as undefined while unsettled.
            solver.atoms.getValue(d.key).let { it.resolved && it.value != Conformance.UNDEFINED && it.settledAt > atom.refinedAt }
        }
    val dependents = HashMap<AtomKey, MutableList<AtomKey>>()
    for ((index, atom) in atoms.withIndex()) {
        atom.inGroup = true
        atom.readUndefined = true
        for (d in atom.dependencies) if (d.key in memberSet) dependents.getOrPut(d.key) { ArrayList() }.add(members[index])
    }
    val work = ArrayDeque<AtomKey>()
    val queued = HashSet<AtomKey>()
    for ((index, atom) in atoms.withIndex()) if (changedSinceRefined(atom) && queued.add(members[index])) work.add(members[index])
    var settled = false
    while (work.isNotEmpty()) {
        ctx.checkDeadline()
        val key = work.removeFirst()
        queued.remove(key)
        val atom = solver.atoms.getValue(key)
        if (atom.resolved) continue
        val result = evaluateAtom(solver, atom, ctx, state)
        if (solver.restart) return false
        atom.refinedAt = solver.epoch
        if (result == Conformance.UNDEFINED) continue
        solver.settle(atom, result)
        settled = true
        dependents[key]?.forEach { reader ->
            if (!solver.atoms.getValue(reader).resolved && queued.add(reader)) work.add(reader)
        }
    }
    for (atom in atoms) if (!atom.resolved) { atom.inGroup = false; atom.readUndefined = false }
    return settled
}

/** Evaluates a solver question in step 2, attributing reads the recording missed to it. */
internal fun NativeShaclValidator.evaluateAtom(solver: RecursionSolver, atom: Atom, ctx: NativeValidationContext, state: DepthState): Conformance {
    val saved = solver.evaluating
    solver.evaluating = atom
    try {
        return evaluateQuestion(AtomKey(TermKey(atom.node), atom.shape), atom.node, atom.shape, ctx, state.depth, exhaustive = false)
    } finally {
        solver.evaluating = saved
    }
}

/** Greatest fixpoint of a group without negative dependencies: assume conformance, lower answers until stable. */
internal fun NativeShaclValidator.greatestFixpoint(solver: RecursionSolver, members: List<AtomKey>, memberSet: Set<AtomKey>, ctx: NativeValidationContext, state: DepthState) {
    val dependents = HashMap<AtomKey, MutableList<AtomKey>>()
    for (key in members) {
        val atom = solver.atoms.getValue(key)
        atom.inGroup = true
        atom.value = Conformance.CONFORMS
        for (d in atom.dependencies) if (d.key in memberSet) dependents.getOrPut(d.key) { ArrayList() }.add(key)
    }
    val work = ArrayDeque<AtomKey>()
    val queued = HashSet<AtomKey>()
    fun lowered(key: AtomKey) {
        dependents[key]?.forEach { if (solver.atoms.getValue(it).value != Conformance.FAILS && queued.add(it)) work.add(it) }
    }
    for (key in members) {
        val atom = solver.atoms.getValue(key)
        // The recorded answer is the first iteration when every read outside the group conforms.
        val reusable = atom.dependencies.all { d -> d.key in memberSet || solver.atoms.getValue(d.key).value == Conformance.CONFORMS }
        val value = if (reusable) atom.optimistic else evaluateAtom(solver, atom, ctx, state)
        if (solver.restart) return
        if (value < atom.value) {
            atom.value = value
            lowered(key)
        }
    }
    while (work.isNotEmpty()) {
        ctx.checkDeadline()
        val key = work.removeFirst()
        queued.remove(key)
        val atom = solver.atoms.getValue(key)
        val result = evaluateAtom(solver, atom, ctx, state)
        if (solver.restart) return
        if (result < atom.value) {
            atom.value = result
            lowered(key)
        }
    }
    for (key in members) solver.atoms.getValue(key).let { solver.settle(it, it.value) }
}

