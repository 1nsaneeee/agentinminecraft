package dev.mcagent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/**
 * Undo history for world edits made by the agent.
 *
 * <p>Only edits that go through this history are restorable, which keeps the
 * semantics honest: this is "undo what the agent did", not "undo the world".
 * Every record captures the old block state and, when present, the old block
 * entity NBT, so restoring a machine brings its contents back too.</p>
 */
public final class EditHistory {
    private static final int MAX_OPERATIONS = 16;
    private static final int MAX_SYNC_UNDO_CELLS = 4096;

    public record Cell(BlockPos pos, BlockState oldState, CompoundTag oldBlockEntity) {}

    /** A null after snapshot means the write outcome could not be inspected. Never undo it blindly. */
    public record Change(Cell before, Cell after) {
        public Change {
            before = copy(before);
            after = after == null ? null : copy(after);
            if (after != null && !before.pos().equals(after.pos())) throw new IllegalArgumentException("change positions differ");
        }
        public boolean changed() {
            return after == null || !same(before, after);
        }
    }

    public record UndoResult(String text, boolean error) {}

    public static final class Operation {
        final String id = UUID.randomUUID().toString();
        final String label;
        final ServerLevel level;
        final List<Change> cells;

        Operation(String label, ServerLevel level, List<Change> cells) {
            this.label = label;
            this.level = level;
            this.cells = List.copyOf(cells);
        }

        public String label() {
            return label;
        }

        public int size() {
            return cells.size();
        }
    }

    private static final List<Operation> STACK = new ArrayList<>();
    private static Operation pinned;

    private EditHistory() {}

    public static synchronized void capture(ServerLevel level, List<BlockPos> positions, String label,
                                           List<Cell> out) {
        HolderLookup.Provider registries = level.registryAccess();
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            CompoundTag tag = be == null ? null : be.saveWithFullMetadata(registries);
            out.add(new Cell(pos.immutable(), state, tag));
        }
    }

    private static Cell copy(Cell cell) {
        return new Cell(cell.pos().immutable(), cell.oldState(), cell.oldBlockEntity() == null ? null : cell.oldBlockEntity().copy());
    }

    private static boolean same(Cell first, Cell second) {
        return first.oldState().equals(second.oldState())
                && java.util.Objects.equals(first.oldBlockEntity(), second.oldBlockEntity());
    }

    /** Capture immediately after this cell's write, not when a multi-tick job eventually finishes. */
    public static Change recordedChange(ServerLevel level, Cell before) {
        BlockEntity be = level.getBlockEntity(before.pos());
        CompoundTag nbt = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
        return new Change(before, new Cell(before.pos(), level.getBlockState(before.pos()), nbt));
    }

    public static synchronized void push(String label, ServerLevel level, List<Cell> cells) {
        // Legacy same-task callers only. Batched jobs must retain their immediate after snapshots.
        List<Change> changes = new ArrayList<>();
        for (Cell cell : cells) changes.add(recordedChange(level, cell));
        pushRecorded(label, level, changes);
    }

    public static synchronized void pushRecorded(String label, ServerLevel level, List<Change> cells) {
        if (cells.isEmpty()) return;
        STACK.add(new Operation(label, level, cells));
        while (STACK.size() > MAX_OPERATIONS) {
            STACK.remove(STACK.get(0) == pinned ? 1 : 0);
        }
    }

    /** Record only cells that really changed, including block entity contents. Server thread only. */
    public static int pushChanged(String label, ServerLevel level, List<Cell> before) {
        List<Change> changed = new ArrayList<>();
        for (Cell cell : before) {
            Change change = recordedChange(level, cell);
            if (change.changed()) changed.add(change);
        }
        pushRecorded(label, level, changed);
        return changed.size();
    }

    public static boolean matches(ServerLevel level, Cell cell) {
        BlockEntity be = level.getBlockEntity(cell.pos());
        CompoundTag after = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
        return cell.oldState().equals(level.getBlockState(cell.pos()))
                && java.util.Objects.equals(cell.oldBlockEntity(), after);
    }

    public static synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        for (int i = STACK.size() - 1; i >= 0; i--) {
            Operation op = STACK.get(i);
            out.add(i + ": " + op.label() + " (" + op.size() + " cells, " + op.level.dimension().location() + ")");
        }
        return out;
    }

    public static synchronized int size() {
        return STACK.size();
    }

    static synchronized List<Operation> records() {
        return List.copyOf(STACK.reversed());
    }

    static synchronized Operation pinHead(String recordId) {
        if (pinned != null || STACK.isEmpty() || !STACK.getLast().id.equals(recordId)) return null;
        pinned = STACK.getLast();
        return pinned;
    }

    static synchronized boolean isHead(Operation operation) {
        return !STACK.isEmpty() && STACK.getLast() == operation;
    }

    static synchronized boolean discardHead(String recordId) {
        if (pinned != null || STACK.isEmpty() || !STACK.getLast().id.equals(recordId)) return false;
        STACK.removeLast();
        return true;
    }

    /** Update this record by identity. An unrelated append must keep its place on the stack. */
    static synchronized Operation finishPinned(Operation operation, BitSet restored, BitSet uncertain) {
        if (pinned != operation) throw new IllegalStateException("undo history pin was lost");
        try {
            int index = STACK.indexOf(operation);
            if (index < 0) throw new IllegalStateException("pinned undo history record was lost");
            if (restored.isEmpty() && uncertain.isEmpty()) return operation;

            List<Change> remaining = new ArrayList<>(operation.size() - restored.cardinality());
            boolean changed = !restored.isEmpty();
            for (int i = 0; i < operation.size(); i++) {
                if (restored.get(i) && !uncertain.get(i)) continue;
                Change entry = operation.cells.get(i);
                if (uncertain.get(i) && entry.after() != null) {
                    entry = new Change(entry.before(), null);
                    changed = true;
                }
                remaining.add(entry);
            }
            if (!changed) return operation;
            if (remaining.isEmpty()) {
                STACK.remove(index);
                return null;
            }
            Operation residue = new Operation(operation.label, operation.level, remaining);
            STACK.set(index, residue);
            return residue;
        } finally {
            pinned = null;
        }
    }

    static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /** Restore one already checked cell and verify its actual block and complete NBT. */
    static void restoreCell(ServerLevel level, Cell cell) {
        restoreCell(level, cell, () -> true);
    }

    static boolean restoreCell(ServerLevel level, Cell cell, java.util.function.BooleanSupplier beforeWrite) {
        CompoundTag saved = cell.oldBlockEntity();
        BlockEntity restoredEntity = saved == null ? null
                : BlockEntity.loadStatic(cell.pos(), cell.oldState(), saved.copy(), level.registryAccess());
        if (saved != null && restoredEntity == null) throw new IllegalStateException("cannot reconstruct saved block entity at " + cell.pos());
        if (!beforeWrite.getAsBoolean()) return false;
        level.setBlock(cell.pos(), cell.oldState(), 3);
        if (!loaded(level, cell.pos()) || !level.getBlockState(cell.pos()).equals(cell.oldState()))
            throw new IllegalStateException("restored block was changed during update at " + cell.pos());
        if (restoredEntity != null) {
            // Recreate from the full snapshot so omitted fields (such as RecipesUsed) cannot survive.
            level.removeBlockEntity(cell.pos());
            level.setBlockEntity(restoredEntity);
            restoredEntity.setChanged();
            level.getChunkSource().blockChanged(cell.pos());
        }
        if (!loaded(level, cell.pos()) || !matches(level, cell))
            throw new IllegalStateException("restored cell differs at " + cell.pos());
        return true;
    }

    public static synchronized UndoResult undoLast() {
        if (pinned != null) return new UndoResult("a batched undo is active; wait or cancel it before undo", true);
        if (STACK.isEmpty()) return null;
        Operation op = STACK.getLast();
        if (op.size() > MAX_SYNC_UNDO_CELLS) {
            return new UndoResult("undo record exceeds 4096 cells; use mc_undo_records and mc_undo_start", true);
        }
        if (GameBridge.server().getLevel(op.level.dimension()) != op.level) {
            return new UndoResult("undo blocked: original dimension is unavailable; history retained", true);
        }
        int conflicts = 0;
        List<String> positions = new ArrayList<>();
        for (Change change : op.cells) {
            if (change.after() == null) return new UndoResult("undo outcome_unknown: an edited cell has no verified after snapshot; no cells restored; history retained", true);
            if (!loaded(op.level, change.before().pos())) return new UndoResult("undo blocked: target chunk is unloaded; no cells restored; history retained", true);
            if (!matches(op.level, change.after())) {
                conflicts++;
                if (positions.size() < 16) positions.add(change.before().pos().toShortString());
            }
        }
        if (conflicts > 0) return new UndoResult("undo conflict: " + conflicts + " cells changed after the edit at "
                + String.join("; ", positions) + "; no cells restored; history retained", true);
        int restored = 0;
        try {
            // Recheck each cell: neighbor updates or mod hooks can mutate later cells during this loop.
            for (int i = op.cells.size() - 1; i >= 0; i--) {
                Change change = op.cells.get(i);
                Cell cell = change.before();
                if (!loaded(op.level, cell.pos()) || !matches(op.level, change.after())) {
                    return new UndoResult("undo stopped: target changed during restoration; " + restored
                            + " cells restored before stopping; partial changes require inspection; history retained", true);
                }
                restoreCell(op.level, cell);
                restored++;
            }
            for (Change change : op.cells) {
                if (!loaded(op.level, change.before().pos()) || !matches(op.level, change.before())) {
                    throw new IllegalStateException("final undo verification failed at " + change.before().pos());
                }
            }
        } catch (RuntimeException error) {
            McAgent.LOG.warn("[mcagent] undo of '{}' may be partial; history retained for inspection", op.label(), error);
            return new UndoResult("undo outcome_unknown after " + restored
                    + " verified cell restorations; inspect actual blocks/NBT before another operation; history retained", true);
        }
        // A synchronous mod hook may have appended history while restoring blocks.
        STACK.remove(op);
        return new UndoResult("undid '" + op.label() + "' (" + restored + " cells)", false);
    }

    public static synchronized void clear() {
        STACK.clear();
        pinned = null;
    }
}
