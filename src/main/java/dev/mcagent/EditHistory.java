package dev.mcagent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Undo history for world edits made by the agent.
 *
 * <p>Only edits that go through {@link #record} are restorable, which keeps the
 * semantics honest: this is "undo what the agent did", not "undo the world".
 * Every record captures the old block state and, when present, the old block
 * entity NBT, so restoring a machine brings its contents back too.</p>
 */
public final class EditHistory {
    private static final int MAX_OPERATIONS = 16;

    public record Cell(BlockPos pos, BlockState oldState, CompoundTag oldBlockEntity) {}

    public static final class Operation {
        final String label;
        final ServerLevel level;
        final List<Cell> cells;

        Operation(String label, ServerLevel level, List<Cell> cells) {
            this.label = label;
            this.level = level;
            this.cells = cells;
        }

        public String label() {
            return label;
        }

        public int size() {
            return cells.size();
        }
    }

    private static final List<Operation> STACK = new ArrayList<>();

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

    public static synchronized void push(String label, ServerLevel level, List<Cell> cells) {
        if (cells.isEmpty()) return;
        STACK.add(new Operation(label, level, cells));
        while (STACK.size() > MAX_OPERATIONS) STACK.remove(0);
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

    public static synchronized String undoLast() {
        if (STACK.isEmpty()) return null;
        Operation op = STACK.remove(STACK.size() - 1);
        HolderLookup.Provider registries = op.level.registryAccess();
        int restored = 0;
        int blockEntityFailures = 0;
        // Reverse order so overlapping writes settle on the earliest state.
        for (int i = op.cells.size() - 1; i >= 0; i--) {
            Cell cell = op.cells.get(i);
            op.level.setBlock(cell.pos(), cell.oldState(), 3);
            if (cell.oldBlockEntity() != null) {
                // setBlock should have created the block entity synchronously; if it
                // did not, say so instead of silently losing the machine contents.
                BlockEntity be = op.level.getBlockEntity(cell.pos());
                if (be == null) {
                    blockEntityFailures++;
                    McAgent.LOG.warn("[mcagent] undo could not restore block entity at {}", cell.pos());
                } else {
                    be.loadWithComponents(cell.oldBlockEntity(), registries);
                    be.setChanged();
                }
            }
            restored++;
        }
        op.level.getChunkSource().blockChanged(op.cells.get(0).pos());
        String result = "undid '" + op.label() + "' (" + restored + " cells)";
        if (blockEntityFailures > 0) {
            result += " — WARNING: " + blockEntityFailures + " block entities could not be restored";
        }
        return result;
    }

    public static synchronized void clear() {
        STACK.clear();
    }
}
