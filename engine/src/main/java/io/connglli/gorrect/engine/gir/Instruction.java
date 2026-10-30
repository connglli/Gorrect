package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Value;
import java.util.List;

/**
 * Immutable GIR statements and their execution contracts.
 *
 * <p>Fresh names exclude all declarations in active scopes, including released bindings. Reference
 * operands are live handles or {@code null}; scalar operands are live scalars or signed 64-bit
 * literals. Slot access requires a live, non-null owner and an in-range index in its shape's
 * reference or data namespace.
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
   * Requires a declared root and shape and a fresh name. Reads the root once into a strong handle,
   * including null. A non-null target must have exactly the declared shape. Later root writes do
   * not change the handle.
   */
  record ReadRoot(int id, String name, String shape, String root, MemoryMode mode)
      implements Operation {
    public ReadRoot(int id, String name, String shape, String root) {
      this(id, name, shape, root, MemoryMode.PLAIN);
    }
  }

  /**
   * Requires an existing root and reference operand. Replaces its target with the operand's current
   * target, of any shape or null. Clearing preserves the root slot.
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
   * Reads a soft/weak slot exactly once. A non-null result must have the declared shape and is held
   * strongly in the child scope while its body executes; null skips the body. The result's binding
   * expires at scope exit and cannot be released early. Checkpoints inside the body are
   * unsupported. Acquisitions consume one step and a successful scope exit consumes one.
   */
  record Acquire(int id, String name, String shape, String owner, int index, List<Instruction> body)
      implements Instruction {
    public Acquire {
      body = List.copyOf(body);
    }
  }

  /**
   * Clears the current wrapper explicitly. This permits clearing a protected referent, without
   * enqueueing it.
   */
  record ClearReference(int id, String owner, int index) implements Operation {}

  /**
   * Binds scalar 1 if refersTo(null) reports cleared, else 0, without acquiring a strong referent.
   */
  record ReferenceState(int id, String name, String owner, int index) implements Operation {}

  /**
   * Polls the case's reference queue once without acquiring a referent. Records the delivered
   * incarnation or an empty result. GC-cleared wrappers may arrive later, in any order, once each;
   * an empty result establishes no processing progress. Initially null wrappers and explicitly
   * cleared wrappers are not enqueued by those actions.
   */
  record PollQueue(int id) implements Operation {}

  /**
   * Requires a valid data-slot access and a fresh destination name. Reads the signed 64-bit value
   * once into a scalar binding. Later slot writes do not change the copied value. The scalar
   * binding does not retain the owner object.
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
   * signed 64-bit value.
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
   * identity; longs compare bits. Binds 1 on success, 0 otherwise, without spurious failure. The
   * read has volatile effects; a successful write does too. Reference locations must contain strong
   * values.
   */
  record CompareSet(int id, String name, Location location, Operand expected, Operand update)
      implements Operation {}

  /**
   * Atomically replaces a location and binds its old value, with volatile read/write effects.
   * Requires a fresh name and matching update type. Reference locations require a declared result
   * shape, including for a null result, and must contain strong values; data locations use a null
   * shape. A returned reference is a strong handle through release/scope exit. Null and
   * target-shape preconditions are checked before model mutation.
   */
  record Exchange(int id, String name, String shape, Location location, Operand update)
      implements Operation {}

  /**
   * Consumes one branch step, executing a child scope iff the existing strong handle is non-null.
   * It does not reread a slot or change the handle's lifetime. Checkpoints in the body are
   * unsupported.
   */
  record IfPresent(int id, String name, List<Instruction> body) implements Instruction {
    public IfPresent {
      body = List.copyOf(body);
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
   * execution appends a visit numbered from one for this instruction. Heap and bindings are
   * unchanged; step and snapshot budgets are charged. Physical binding obligations depend on the
   * observation profile. It does not request garbage collection.
   */
  record Checkpoint(int id) implements Operation {}

  /**
   * Collection request. Preserves logical graph state and bindings; the VM integration decides how
   * to request collection and records its actual progress separately. Consumes one step.
   */
  record Collect(int id) implements Operation {}

  /**
   * Worker barrier, permitted only directly in a worker body. All workers finish the preceding
   * phase before any starts the next. Bindings survive. Every worker must have the same barrier
   * count. A barrier and the following phase-start marker each consume one step.
   */
  record Barrier(int id) implements Operation {}

  /**
   * Starts workers together with independent scopes and shared roots; the caller continues after
   * all join. Capturing caller bindings, nested parallelism, and worker checkpoints are
   * unsupported.
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
