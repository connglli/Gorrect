package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Value;
import java.util.List;

/**
 * Immutable GIR statements and their execution contracts.
 *
 * <p>Fresh names exclude all declarations in the current frame's active scopes, including released
 * bindings. Reference operands are live handles or {@code null}; scalar operands are live scalars
 * or signed 64-bit literals. Slot access requires a live, non-null owner and an in-range index in
 * its shape's reference or data namespace.
 *
 * <p>Statements change only the specified state. {@link GirValidator} checks static contracts;
 * execution checks null owners and target shapes. Invalid preconditions raise {@link GirException};
 * exhausted checking budgets raise {@link io.connglli.gorrect.engine.oracle.OracleLimitException}.
 * Read/write effects follow {@link MemoryMode}; atomic operations always have volatile effects.
 */
public sealed interface Instruction {
  /** Internal preorder index, assigned by the frontend. */
  int id();

  /**
   * Invokes a statically named void helper with copied reference/long arguments. Entry validates
   * every argument and capacity before opening an isolated frame or recording CALL_ENTER. The
   * caller's live handles remain roots. Fallthrough cleans the frame before charging CALL_EXIT;
   * failures keep completed effects and markers, always restore the caller, and emit no exit.
   */
  record Call(int id, String method, List<Operand> arguments) implements Instruction {
    public Call {
      arguments = List.copyOf(arguments);
    }
  }

  /**
   * A primitive consumes one step on success. Rejection for an invalid precondition or exhausted
   * budget leaves the heap, bindings, identities, and counters unchanged.
   */
  sealed interface Operation extends Instruction {}

  /**
   * Requires a fresh name and declared shape. Allocates a distinct object with null references and
   * zero data, binding a strong handle to it. Identity uses this site and its next occurrence.
   */
  record Allocate(int id, String name, String shape) implements Operation {}

  /**
   * Requires a fresh result name and a live non-null array handle. Allocates a distinct array with
   * the same nominal shape and shallow contents: reference targets are shared, but later array
   * writes are independent. Uses this site's next allocation occurrence and normal object/slot
   * budgets. Invalid owners and exhausted budgets reject before any model effect. The first
   * implementation supports strong sequential programs under controlled observation.
   */
  record Clone(int id, String name, String owner) implements Operation {}

  /**
   * Copies a complete range between live non-null arrays of the same supported kind. Ranges use
   * nonnegative literals and are validated even for zero length. Contents are shallow: targets
   * remain shared, identities and values outside the destination range remain unchanged. Overlap
   * uses the source contents before this operation. No logical allocation or slot budget is used;
   * invalid operands/ranges and exhausted step budgets reject before effects. Initial support is
   * limited to strong sequential programs under controlled observation.
   */
  record CopyArray(
      int id, String source, int sourceIndex, String destination, int destinationIndex, int length)
      implements Operation {}

  /**
   * Observes System.identityHashCode once for a live reference or null, without binding a scalar.
   * The signed int is opaque: null requires zero; non-null zero and collisions are legal. Repeated
   * observations of one allocation must agree, including across payload changes and collections.
   * The call may affect headers, materialization, escape analysis and timing; it is a workload
   * effect. The initial contract requires strong sequential controlled observation.
   */
  record IdentityHash(int id, Operand owner) implements Operation {}

  /**
   * Constructs a fresh soft/weak wrapper. Omitted queue means no registration; a named queue
   * requires its exact nominal shape. A nonliteral-null registered construction must occur once at
   * top level. At least one top-level publication must put the constructor binding into a dedicated
   * initially-null root before binding release, with no other writes to that root; other roots and
   * aliases retain their contracts. Registration is immutable and creates no strong root to the
   * wrapper. Automatic delivery also requires independently qualified target mutation-lock capture.
   */
  record AllocateReference(int id, String name, String shape, Operand value, String queue)
      implements Operation {
    public AllocateReference(int id, String name, String shape, Operand value) {
      this(id, name, shape, value, null);
    }
  }

  /**
   * Requires a live non-null wrapper receiver and fresh scalar name. Calls enqueue once, clearing
   * its referent even on failure. Binds long 1 only for first insertion into its registered queue,
   * otherwise 0. Membership survives repeated false insertion; consumed wrappers cannot be inserted
   * again. No-queue receivers return 0. A pending handler may insert before API linearization;
   * neither this call nor program clearing creates GC-notification debt.
   */
  record EnqueueReference(int id, String name, String owner) implements Operation {}

  /**
   * Requires a declared root and shape and a fresh name. Reads the root once into a strong handle,
   * including null. A non-null target must have exactly the declared shape. Later root writes do
   * not change the handle. JNI storage acquires a local reference and checks the nominal shape
   * before binding; failed acquisition preserves the slot and existing bindings.
   */
  record ReadRoot(int id, String name, String shape, String root, MemoryMode mode)
      implements Operation {
    public ReadRoot(int id, String name, String shape, String root) {
      this(id, name, shape, root, MemoryMode.PLAIN);
    }
  }

  /**
   * Requires an existing root and reference operand. Replaces its target with the operand's current
   * target, of any shape or null. Clearing preserves the root slot. Source-qualified JNI storage
   * acquires a replacement global before deleting the previous one; failed acquisition preserves
   * the slot. Scope exit and release affect Java bindings only. Native storage requires strong
   * sequential controlled programs with plain accesses.
   */
  record WriteRoot(int id, String root, Operand value, MemoryMode mode) implements Operation {
    public WriteRoot(int id, String root, Operand value) {
      this(id, root, value, MemoryMode.PLAIN);
    }
  }

  /**
   * Requires a valid reference-slot access, declared destination shape, and fresh name. Reads the
   * strong slot once and binds a new strong handle to its current target, including null. Soft/weak
   * slots require {@link Acquire}. A non-null target must have exactly the destination shape. Later
   * writes to the source slot do not change the handle.
   */
  record ReadReference(int id, String name, String shape, String owner, int index, MemoryMode mode)
      implements Operation {
    public ReadReference(int id, String name, String shape, String owner, int index) {
      this(id, name, shape, owner, index, MemoryMode.PLAIN);
    }
  }

  /**
   * Requires a valid reference slot and reference operand. Replaces its value with the operand's
   * current target, of any shape or null. Strong writes store it directly; soft/weak writes create
   * a fresh wrapper incarnation, even for null.
   */
  record WriteReference(
      int id, String owner, int index, Operand value, Value.ReferenceKind kind, MemoryMode mode)
      implements Operation {
    public WriteReference(int id, String owner, int index, Operand value) {
      this(id, owner, index, value, Value.ReferenceKind.STRONG, MemoryMode.PLAIN);
    }

    public WriteReference(
        int id, String owner, int index, Operand value, Value.ReferenceKind kind) {
      this(id, owner, index, value, kind, MemoryMode.PLAIN);
    }
  }

  /**
   * Reads a soft/weak slot or first-class receiver exactly once. The receiver flag denotes omitted
   * index syntax; explicit negative legacy indices remain invalid. A non-null result must have the
   * declared shape and is held strongly in the child scope while its body executes; null skips the
   * body. The result's binding expires at scope exit and cannot be released early. Checkpoints in
   * the body include the acquired handle among their binding obligations. Acquisitions consume one
   * step and a successful scope exit consumes one.
   */
  record Acquire(
      int id,
      String name,
      String shape,
      String owner,
      int index,
      List<Instruction> body,
      boolean receiver)
      implements Instruction {
    public Acquire {
      body = List.copyOf(body);
      if (receiver && index != -1) throw new IllegalArgumentException("Invalid receiver encoding");
    }

    public Acquire(
        int id, String name, String shape, String owner, int index, List<Instruction> body) {
      this(id, name, shape, owner, index, body, false);
    }
  }

  /**
   * Clears the current slot wrapper or first-class receiver explicitly. Receiver aliases share that
   * canonical clear state. This permits clearing a protected referent without enqueueing it.
   */
  record ClearReference(int id, String owner, int index, boolean receiver) implements Operation {
    public ClearReference {
      if (receiver && index != -1) throw new IllegalArgumentException("Invalid receiver encoding");
    }

    public ClearReference(int id, String owner, int index) {
      this(id, owner, index, false);
    }
  }

  /**
   * Binds scalar 1 if refersTo(null) reports cleared for the slot wrapper or first-class receiver,
   * else 0, without acquiring a strong referent.
   */
  record ReferenceState(int id, String name, String owner, int index, boolean receiver)
      implements Operation {
    public ReferenceState {
      if (receiver && index != -1) throw new IllegalArgumentException("Invalid receiver encoding");
    }

    public ReferenceState(int id, String name, String owner, int index) {
      this(id, name, owner, index, false);
    }
  }

  /**
   * Named form polls its queue once and binds the actual returned wrapper strongly, or null, using
   * the queue's nominal type. It consumes exactly one member in any order; null requires an empty
   * queue. The result name is fresh. Resource lifetime and prior insertion history are unchanged.
   *
   * <p>Bare legacy form discards the returned wrapper from its separate implicit queue. GC-cleared
   * incarnations may arrive later, in any order, once each; an empty legacy result establishes no
   * progress. Initial null and explicit clear alone create no automatic delivery.
   */
  record PollQueue(int id, String name, String queue) implements Operation {
    public PollQueue(int id) {
      this(id, null, null);
    }

    public boolean named() {
      return queue != null;
    }
  }

  /**
   * Requires a valid data-slot access and a fresh destination name. Reads once into a signed 64-bit
   * scalar binding, sign-extending int fields. Later slot writes do not change the copied value.
   * The scalar binding does not retain the owner object.
   */
  record ReadData(int id, String name, String owner, int index, MemoryMode mode)
      implements Operation {
    public ReadData(int id, String name, String owner, int index) {
      this(id, name, owner, index, MemoryMode.PLAIN);
    }
  }

  /**
   * Binds scalar 1 when the two strong operands denote the same object, including two nulls;
   * otherwise binds 0. Requires a fresh scalar name. Reads existing handles, never a heap slot, and
   * neither acquires a referent nor extends a binding's lifetime.
   */
  record SameObject(int id, String name, Operand left, Operand right) implements Operation {}

  /**
   * Requires a valid data slot and scalar operand. Replaces that slot with the operand's current
   * value, narrowed to int for an int field; long fields preserve all bits.
   */
  record WriteData(int id, String owner, int index, Operand value, MemoryMode mode)
      implements Operation {
    public WriteData(int id, String owner, int index, Operand value) {
      this(id, owner, index, value, MemoryMode.PLAIN);
    }
  }

  /**
   * Requires a fresh scalar name, a valid location, and matching operand types. Atomically compares
   * the current value with expected and writes update only on equality. References compare
   * identity; data operands first convert to the target field width and compare bits. Binds 1 on
   * success, 0 otherwise, without spurious failure. The read has volatile effects; a successful
   * write does too. Reference locations must contain strong values.
   */
  record CompareSet(int id, String name, Location location, Operand expected, Operand update)
      implements Operation {}

  /**
   * Atomically replaces a location and binds its old value, with volatile read/write effects. Data
   * updates convert to the target width; an int old value is sign-extended into a scalar binding.
   * Requires a fresh name and matching update type. Reference locations require a declared result
   * shape, including for a null result, and must contain strong values; data locations use a null
   * shape. A returned reference is a strong handle through release/scope exit. Null and
   * target-shape preconditions are checked before model mutation.
   */
  record Exchange(int id, String name, String shape, Location location, Operand update)
      implements Operation {}

  /**
   * Consumes one branch step, executing a child scope iff the existing strong handle is non-null.
   * It does not reread a slot or change the handle's lifetime.
   */
  record IfPresent(int id, String name, List<Instruction> body) implements Instruction {
    public IfPresent {
      body = List.copyOf(body);
    }
  }

  /**
   * Evaluates an existing scalar or signed long literal once. Zero selects the else arm; every
   * nonzero value selects the then arm. Records one branch step with value zero or one. Each arm
   * has its own scope, exports no bindings, and cannot release outer bindings; heap/root writes
   * persist. Both arms require static validation even when the condition is constant.
   */
  record IfNonzero(
      int id, Operand condition, List<Instruction> thenBody, List<Instruction> elseBody)
      implements Instruction {
    public IfNonzero {
      thenBody = List.copyOf(thenBody);
      elseBody = List.copyOf(elseBody);
    }
  }

  /**
   * Requires a live reference or scalar binding declared in the current scope. Makes it unavailable
   * for subsequent reads and removes its handle obligation, if any. Its name remains reserved until
   * scope exit. Roots and heap slots referencing the same object retain their values.
   */
  record Release(int id, String name) implements Operation {}

  /**
   * Records immutable obligations after preceding statements: named roots, live reference handles,
   * and their accessible closure with exact contents under the selected legal history. Each
   * execution appends a visit numbered from one for this instruction; skipped conditional bodies
   * contribute no visits. Heap and bindings are unchanged; step and snapshot budgets are charged.
   * Physical binding obligations depend on the observation profile. Conditional checkpoints require
   * controlled binding observation. A direct worker checkpoint pauses every worker and the caller
   * at a matched synchronization cut, captures their combined bindings, then starts the next phase.
   * The first worker's site and visit identify that single capture. Worker synchronization kinds
   * must match in declaration order; conditional capture groups are unsupported. It does not
   * request garbage collection.
   */
  record Checkpoint(int id) implements Operation {}

  /**
   * Collection request. Preserves logical graph state and bindings; the VM integration decides how
   * to request collection and records its actual progress separately. Consumes one step.
   */
  record Collect(int id) implements Operation {}

  /**
   * Worker barrier, permitted only directly in a worker body. All workers finish the preceding
   * phase before any starts the next. Bindings survive. Every worker must have the same sequence of
   * barrier and checkpoint kinds. A barrier and its following phase-start marker each consume one
   * step.
   */
  record Barrier(int id) implements Operation {}

  /**
   * Starts workers together with independent scopes and shared roots; the caller continues after
   * all join. Coordinated worker checkpoints include suspended caller bindings. Nested parallelism
   * is unsupported.
   *
   * <p>The exact profile requires conflict-free phases; history profiles require race freedom under
   * explicit synchronization. Concrete checks include aliases and precede target execution. The
   * block, worker start/end, phase-start, and caller join markers each consume one step.
   */
  record Parallel(int id, List<Worker> workers) implements Instruction {
    public Parallel {
      workers = List.copyOf(workers);
    }
  }

  /**
   * A named declaration with an internal declaration-order worker ID, separate from instruction
   * IDs.
   */
  record Worker(int id, String name, List<Instruction> body) {
    public Worker {
      body = List.copyOf(body);
    }
  }

  /**
   * Consumes one control step, then executes the body in a child scope with outer bindings visible.
   * Exiting the block releases its own bindings, including on failure. Root and heap writes
   * persist; the block is not a transaction that rolls back preceding successful operations.
   */
  record Scope(int id, List<Instruction> body) implements Instruction {
    public Scope {
      body = List.copyOf(body);
    }
  }

  /**
   * Requires a live non-null ordinary object or array handle declared in an outer scope. Entry
   * validates the receiver and step capacity before effects, then records OPERATION value 0 after
   * Java acquisition. The child scope sees outer bindings but cannot release or shadow them. Normal
   * completion discards child bindings without budget, then consumes an exit step and records
   * OPERATION value 1 after Java release. An empty block costs two steps.
   *
   * <p>Body failure unwinds child bindings without budget and preserves successful prefix writes;
   * unwound blocks emit no normal-exit marker. Exit exhaustion retains entry/body effects after
   * child cleanup. Unexpected target exceptions propagate after Java release. Strong sequential
   * controlled observation is required. Markers and bytecode do not certify physical ownership,
   * inflation or survival of a lock through optimization. Interior checkpoints require actual
   * local-origin evidence for every live binding, including the receiver.
   */
  record Monitor(int id, String owner, List<Instruction> body) implements Instruction {
    public Monitor {
      body = List.copyOf(body);
    }
  }

  /**
   * Requires a nonnegative literal count. Executes the body that many times, unless execution
   * fails, using a fresh child scope each iteration. Root and heap changes carry between
   * iterations; local bindings expire at each iteration's end. Zero skips execution but still
   * requires a statically valid body. Charges one control step plus one per iteration, even for an
   * empty body.
   */
  record Repeat(int id, long count, List<Instruction> body) implements Instruction {
    public Repeat {
      body = List.copyOf(body);
    }
  }
}
